package com.betterxcloud.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.betterxcloud.app.databinding.ActivityAuthBinding
import java.io.BufferedReader

/**
 * Loads xbox.com/play in a hidden (off-screen) WebView, injects the Better xCloud
 * userscript, and detects when the user has signed in. Once signed in, finishes
 * and returns to MainActivity which then shows the native home screen.
 *
 * The WebView is kept alive across activity transitions by attaching it to
 * [BxApp.sharedWebView].
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val tag = "AuthActivity"

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var micPermissionLauncher: ActivityResultLauncher<String>

    private var sessionObserver: Observer<BxApp.SessionState>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            val uris: Array<Uri>? = if (result.resultCode != RESULT_OK) null
                else result.data?.data?.let { arrayOf(it) }
                    ?: result.data?.clipData?.let { cd -> Array(cd.itemCount) { cd.getItemAt(it).uri } }
            callback.onReceiveValue(uris)
            filePathCallback = null
        }
        micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            Log.i(tag, "Mic permission granted=$it")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = BxApp.instance.sharedWebView
                if (wv != null && wv.canGoBack()) wv.goBack()
                else finish()
            }
        })

        // If we already have a shared WebView from a previous launch, reuse it.
        val existing = BxApp.instance.sharedWebView
        if (existing != null) {
            Log.i(tag, "Reusing existing shared WebView")
            attachExistingWebView(existing)
            return
        }

        setupWebView()
        loadXbox()
    }

    override fun onResume() {
        super.onResume()
        // Observe session — once signed in, finish and go to Home.
        if (sessionObserver == null) {
            val obs = Observer<BxApp.SessionState> { state ->
                if (state == BxApp.SessionState.SIGNED_IN) {
                    Log.i(tag, "User signed in — returning to Home")
                    BxApp.instance.sharedWebView?.visibility = View.GONE
                    binding.root.postDelayed({ finish() }, 300)
                }
            }
            sessionObserver = obs
            (application as BxApp).sessionState.observe(this, obs)
        }
        BxApp.instance.bridge?.startPolling()
    }

    override fun onPause() {
        super.onPause()
        BxApp.instance.bridge?.stopPolling()
    }

    override fun onDestroy() {
        // Detach (but DON'T destroy) the WebView so it survives across activity recreations.
        BxApp.instance.detachWebView()
        sessionObserver?.let { (application as BxApp).sessionState.removeObserver(it) }
        sessionObserver = null
        super.onDestroy()
    }

    private fun attachExistingWebView(webView: WebView) {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        binding.webviewContainer.addView(webView)
        // If already signed in, just finish
        if ((application as BxApp).sessionState.value == BxApp.SessionState.SIGNED_IN) {
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = WebView(this)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            savePassword = false
            saveFormData = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = BxConstants.DESKTOP_UA
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            textZoom = 100
        }

        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = AuthWebViewClient(this)
        webView.webChromeClient = AuthChromeClient(this)

        val bridge = XcloudBridge(application as BxApp, webView)
        webView.addJavascriptInterface(bridge, "BxAndroid")

        // Register with the app singleton
        (application as BxApp).attachWebView(webView, bridge)

        binding.webviewContainer.addView(webView)
    }

    private fun loadXbox() {
        val url = intent?.data?.toString() ?: BxConstants.XBOX_PLAY_URL
        Log.i(tag, "Loading URL: $url")
        BxApp.instance.sharedWebView?.loadUrl(url)
    }

    private fun readBundledUserscript(): String {
        val raw = resources.openRawResource(R.raw.better_xcloud_user)
        return raw.bufferedReader().use(BufferedReader::readText)
    }

    /**
     * Injects the Better xCloud userscript with `BX_FLAGS.SafariWorkaround=false`.
     *
     * The userscript checks `document.readyState === 'loading'` and, if not,
     * throws the "Falha ao executar o Better xCloud" overlay that links to
     * https://better-xcloud.github.io/troubleshooting/. This happens because
     * Android's WebViewClient.onPageStarted fires *after* the document starts
     * parsing, so `readyState` is already 'loading' or even 'interactive'.
     *
     * Setting SafariWorkaround=false disables that workaround entirely (it
     * was only ever needed on iOS Safari). On Android this is safe.
     *
     * Additionally we inject a tiny preamble that sets BX_FLAGS *before* the
     * userscript body runs, so the script reads the override correctly.
     */
    internal fun injectBetterXcloud(webView: WebView) {
        try {
            val script = readBundledUserscript()
            // Preamble: override BX_FLAGS BEFORE the userscript reads them.
            // The userscript does: `BX_FLAGS = Object.assign(DEFAULT_FLAGS, window.BX_FLAGS || {})`
            // so setting window.BX_FLAGS first lets us override any default flag.
            val preamble = """
                (function(){
                  window.BX_FLAGS = Object.assign(window.BX_FLAGS || {}, {
                    SafariWorkaround: false,
                    Debug: false,
                    CheckForUpdate: false,
                    EnableXcloudLogging: false,
                    DeviceInfo: {
                      deviceType: 'android',
                      userAgent: navigator.userAgent
                    }
                  });
                })();
            """.trimIndent()

            val wrapped = "(function(){\n$preamble\n$script\n})();"
            webView.evaluateJavascript(wrapped, null)
            Log.i(tag, "Better xCloud userscript injected (${script.length} chars)")
        } catch (t: Throwable) {
            Log.e(tag, "Failed to inject userscript", t)
        }
    }

    // ------------------------------------------------------------------
    // WebViewClient
    // ------------------------------------------------------------------
    private class AuthWebViewClient(private val activity: AuthActivity) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url ?: return false
            val host = url.host ?: return false
            val internalHosts = listOf(
                "xbox.com", "www.xbox.com",
                "xcloud.com", "www.xcloud.com",
                "microsoft.com", "www.microsoft.com",
                "login.live.com", "login.microsoftonline.com",
                "account.microsoft.com",
                "login.xboxlive.com",
                "msftauth.net"
            )
            val isInternal = internalHosts.any { host == it || host.endsWith(".$it") }
            if (isInternal) return false
            return try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (t: Throwable) { false }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.let { activity.injectBetterXcloud(it) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.let { activity.injectBetterXcloud(it) }
            // Start polling for auth state
            (activity.application as BxApp).bridge?.startPolling()
        }
    }

    // ------------------------------------------------------------------
    // WebChromeClient — mic permission + file chooser
    // ------------------------------------------------------------------
    private class AuthChromeClient(private val activity: AuthActivity) : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            val granted = request.resources.filter { r ->
                when (r) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                        activity.micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        true
                    }
                    else -> false
                }
            }
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        }

        override fun onShowFileChooser(
            webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
        ): Boolean {
            activity.filePathCallback?.onReceiveValue(null)
            activity.filePathCallback = callback
            val intent = params?.createIntent() ?: return false
            return try { activity.fileChooserLauncher.launch(intent); true }
            catch (t: Throwable) { activity.filePathCallback = null; false }
        }
    }
}
