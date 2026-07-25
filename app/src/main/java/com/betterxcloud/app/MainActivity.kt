package com.betterxcloud.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
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
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.betterxcloud.app.databinding.ActivityMainBinding
import java.io.BufferedReader

private const val TAG = "BetterXCloud"

private const val XBOX_PLAY_URL = "https://www.xbox.com/play"

// Desktop Edge on Windows UA — forces xbox.com to serve the full desktop xCloud experience
// instead of the limited mobile web flow.
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/124.0.0.0 Safari/537.36 Edg/124.0.2478.0"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // File chooser callback (for screenshot saving / upload dialogs inside the WebView)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Microphone permission launcher — requested by WebChromeClient.onPermissionRequest
    private val micPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "Microphone permission granted=$granted")
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge + immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()
        setupBackNavigation()

        if (savedInstanceState == null) {
            loadXcloud()
        } else {
            binding.webview.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webview.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onPause() {
        super.onPause()
        binding.webview.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webview.onResume()
        setupImmersiveMode()
    }

    override fun onDestroy() {
        binding.webview.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    // Keep hardware Back button navigating WebView history instead of finishing the activity
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.canGoBack()) {
                    binding.webview.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupImmersiveMode() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadXcloud() {
        val webView = binding.webview

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // WebGL / WebGPU / hardware acceleration
            allowFileAccess = false
            allowContentAccess = false

            // Auth flows / target=_blank links navigate the same WebView
            // (we don't implement WebChromeClient.onCreateWindow, so multiple
            // windows would silently fail to open otherwise).
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)

            // Force desktop layout
            useWideViewPort = true
            loadWithOverviewMode = true

            // Don't save form data / passwords in the WebView — let Xbox handle auth
            savePassword = false
            saveFormData = false

            // Media playback without user gesture
            mediaPlaybackRequiresUserGesture = false

            // Mixed content: xcloud uses HTTPS only, but some assets may be HTTP
            mixedContentMode =
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // User-Agent override — desktop Edge on Windows
            userAgentString = DESKTOP_UA

            // Cache
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

            // Text zoom — keep at 100% so xcloud's UI scales correctly
            textZoom = 100
        }

        // Enable WebView debugging in debug builds
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
            applicationContext.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.webViewClient = XcloudWebViewClient(this)
        webView.webChromeClient = XcloudChromeClient(this)

        // Optional: expose a tiny bridge so the injected script can ask the app
        // for native things (e.g. open external links, share, etc.)
        webView.addJavascriptInterface(NativeBridge(), "BxAndroid")

        val url = intent?.data?.toString() ?: XBOX_PLAY_URL
        Log.i(TAG, "Loading URL: $url")
        webView.loadUrl(url)
    }

    /**
     * Reads the bundled Better xCloud userscript from res/raw and returns it as a string,
     * ready to be injected via WebView.evaluateJavascript().
     */
    private fun readBundledUserscript(): String {
        val raw = resources.openRawResource(R.raw.better_xcloud_user)
        return raw.bufferedReader().use(BufferedReader::readText)
    }

    /**
     * Injects the Better xCloud userscript into the WebView.
     * Called by the WebViewClient on every page navigation that matches the xcloud play URL.
     *
     * The userscript is wrapped in an IIFE so it never leaks globals into the page scope
     * beyond what the script itself chooses to attach (window.BxC, window.BX_FLAGS, etc.).
     */
    internal fun injectBetterXcloud(webView: WebView) {
        try {
            val script = readBundledUserscript()
            // The userscript already starts with "use strict" and is minified;
            // wrap in a closure to avoid leaking `var` declarations into global scope.
            val wrapped = "(function(){\n$script\n})();"
            // evaluateJavascript runs in the page's main world (same context as page scripts),
            // which is exactly what a userscript needs.
            webView.evaluateJavascript(wrapped, null)
            Log.i(TAG, "Better xCloud userscript injected (${script.length} chars)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to inject Better xCloud userscript", t)
        }
    }

    // ------------------------------------------------------------------
    // Activity result for file chooser (used by WebChromeClient.onShowFileChooser)
    // ------------------------------------------------------------------
    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            val intent = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != Activity.RESULT_OK -> null
                intent?.data != null -> arrayOf(intent.data!!)
                intent?.clipData != null -> {
                    val cd = intent.clipData!!
                    Array(cd.itemCount) { cd.getItemAt(it).uri }
                }
                else -> null
            }
            callback.onReceiveValue(uris)
            filePathCallback = null
        }

    // ------------------------------------------------------------------
    // Native bridge — minimal, exposed to the page as window.BxAndroid
    // ------------------------------------------------------------------
    inner class NativeBridge {
        @android.webkit.JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (t: Throwable) {
                    Toast.makeText(this@MainActivity, "No app to open: $url", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun toast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @android.webkit.JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }

    // ------------------------------------------------------------------
    // WebViewClient — intercepts navigations, injects the userscript
    // ------------------------------------------------------------------
    private class XcloudWebViewClient(private val activity: MainActivity) : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url ?: return false
            val host = url.host ?: return false

            // Keep xbox.com / xcloud.com / microsoft auth hosts inside the WebView
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

            // Everything else (true-achievements, etc.) opens externally
            return try {
                val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                true
            } catch (t: Throwable) {
                false
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // Inject the userscript as early as possible — this is the closest
            // equivalent to Tampermonkey's @run-at document-start.
            view?.let { activity.injectBetterXcloud(it) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // Re-inject in case the page navigated away from the play URL and back.
            // The userscript itself checks BX_FLAGS and is safe to call multiple times.
            view?.let { activity.injectBetterXcloud(it) }
        }
    }

    // ------------------------------------------------------------------
    // WebChromeClient — handles file chooser, permissions (mic), fullscreen video
    // ------------------------------------------------------------------
    private class XcloudChromeClient(private val activity: MainActivity) : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            // xCloud asks for microphone permission when entering voice chat
            val resources = request.resources
            val granted = mutableListOf<String>()
            for (r in resources) {
                when (r) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                        activity.micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        granted.add(r)
                    }
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                        // Not needed for xcloud — explicitly deny
                    }
                    else -> granted.add(r)
                }
            }
            if (granted.isEmpty()) {
                request.deny()
            } else {
                request.grant(granted.toTypedArray())
            }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            callback: ValueCallback<Array<Uri>>?,
            params: FileChooserParams?
        ): Boolean {
            activity.filePathCallback?.onReceiveValue(null)
            activity.filePathCallback = callback
            val intent = params?.createIntent() ?: return false
            return try {
                activity.fileChooserLauncher.launch(intent)
                true
            } catch (t: Throwable) {
                activity.filePathCallback = null
                false
            }
        }

        // Fullscreen video / stream support
        private var customView: View? = null
        private var originalSystemUiVisibility: Int = 0

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            activity.binding.root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            activity.binding.webview.visibility = View.GONE
            originalSystemUiVisibility = activity.window.decorView.systemUiVisibility
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView() {
            activity.binding.root.removeView(customView)
            customView = null
            activity.binding.webview.visibility = View.VISIBLE
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
