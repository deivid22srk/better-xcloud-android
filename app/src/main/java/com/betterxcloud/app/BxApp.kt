package com.betterxcloud.app

import android.app.Application
import android.webkit.WebView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * App-level singleton. Holds:
 * - The shared WebView (kept alive while the app process is alive, so cookies,
 *   localStorage, and the auth session persist across activity recreations).
 * - The current session state (signed-in gamertag, avatar, library).
 * - The current Better xCloud settings cache.
 */
class BxApp : Application() {

    enum class SessionState { LOADING, SIGNED_IN, SIGNED_OUT, ERROR }

    private val _sessionState = MutableLiveData(SessionState.LOADING)
    val sessionState: LiveData<SessionState> = _sessionState

    var gamertag: String? = null
        private set
    var avatarUrl: String? = null
        private set

    /** The shared WebView — created lazily, kept across activity recreations. */
    @Volatile
    var sharedWebView: WebView? = null
        internal set

    /** JS bridge exposed as window.BxAndroid on the shared WebView. */
    @Volatile
    var bridge: XcloudBridge? = null
        internal set

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    fun setSession(state: SessionState, gamertag: String? = null, avatarUrl: String? = null) {
        if (gamertag != null) this.gamertag = gamertag
        if (avatarUrl != null) this.avatarUrl = avatarUrl
        _sessionState.postValue(state)
    }

    fun attachWebView(webView: WebView, bridge: XcloudBridge) {
        this.sharedWebView = webView
        this.bridge = bridge
    }

    fun detachWebView() {
        // Don't destroy — keep the WebView alive for the next activity to attach.
        // The sharedWebView's parent is cleared by the activity on finish.
        sharedWebView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
    }

    /**
     * Clears the shared WebView reference (used by sign-out). The activity that
     * called this is responsible for actually destroying the WebView via
     * WebView.destroy() after clearing cookies/storage.
     */
    fun clearWebView() {
        sharedWebView = null
        bridge = null
    }

    companion object {
        @Volatile
        lateinit var instance: BxApp
            private set
    }
}
