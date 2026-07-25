package com.betterxcloud.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.BufferedReader

/**
 * Fullscreen streaming activity. Reuses the shared WebView from [BxApp], so
 * cookies, localStorage, and the auth session stay warm. Navigates to the
 * xCloud deep-link for the selected title.
 *
 * The deep link `https://www.xbox.com/play/games/<titleId>` launches xCloud's
 * stream session for that title directly.
 */
class StreamActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var customView: View? = null
    private val tag = "StreamActivity"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pure fullscreen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val titleId = intent.getStringExtra(EXTRA_TITLE_ID) ?: run {
            Log.e(tag, "No title id provided")
            finish()
            return
        }
        val titleName = intent.getStringExtra(EXTRA_TITLE_NAME) ?: "Game"

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        setContentView(container)

        // Use the shared WebView — keeps session, cookies, localStorage.
        webView = BxApp.instance.sharedWebView ?: WebView(this).also {
            // Cold path: shouldn't normally happen, but handle gracefully.
            BxApp.instance.attachWebView(it, XcloudBridge(BxApp.instance, it))
        }
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        container.addView(webView)

        setupImmersive()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
                    return
                }
                if (webView.canGoBack()) webView.goBack()
                else finish()
            }
        })

        // Navigate to the deep link for this title
        val deepLink = "https://www.xbox.com/play/games/$titleId"
        Log.i(tag, "Launching stream: $deepLink")
        webView.loadUrl(deepLink)
    }

    private fun setupImmersive() {
        WindowInsetsControllerCompat(window, webView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    override fun onResume() {
        super.onResume()
        setupImmersive()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        // Detach (don't destroy) the shared WebView so Home can reuse it.
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        super.onDestroy()
    }

    private fun readBundledUserscript(): String {
        val raw = resources.openRawResource(R.raw.better_xcloud_user)
        return raw.bufferedReader().use(BufferedReader::readText)
    }

    internal fun injectBetterXcloud(view: WebView) {
        try {
            val script = readBundledUserscript()
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
            view.evaluateJavascript(wrapped, null)
        } catch (t: Throwable) {
            Log.e(tag, "Failed to inject userscript", t)
        }
    }

    /**
     * The shared WebView's WebViewClient/ChromeClient live on the WebView itself,
     * so the auth ones keep working. We just attach an additional handler for
     * fullscreen video by wrapping.
     *
     * In practice the AuthActivity's chrome client already handles fullscreen.
     * For the stream session we just need to make sure we re-inject the
     * userscript on every navigation (which the auth client already does).
     */
    companion object {
        const val EXTRA_TITLE_ID = "com.betterxcloud.app.TITLE_ID"
        const val EXTRA_TITLE_NAME = "com.betterxcloud.app.TITLE_NAME"
    }
}
