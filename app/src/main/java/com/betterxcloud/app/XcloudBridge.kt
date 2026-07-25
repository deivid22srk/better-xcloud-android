package com.betterxcloud.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript bridge exposed to the WebView as `window.BxAndroid`.
 *
 * Responsibilities:
 * 1. Detect when the user is signed in (the userscript / xbox.com SPA sets
 *    `window.xbcUser.isSignedIn` once authentication completes).
 * 2. Read the auth session (gamertag, avatar, gsToken) from the page's JS context.
 * 3. Read/write Better xCloud settings in localStorage.
 * 4. Fetch the user's Game Pass Cloud library (uses the page's cookies via
 *    fetch() from the page context — no token juggling on the native side).
 * 5. Trigger a stream session (xbox.com/play deep-link).
 */
class XcloudBridge(private val app: BxApp, private val webView: WebView) {

    private val handler = Handler(Looper.getMainLooper())
    private val logTag = "BxAndroid"

    // ------------------------------------------------------------------
    // Polling loop — invoked from Kotlin to detect auth state changes.
    // ------------------------------------------------------------------
    private var pollCount = 0

    fun startPolling() {
        handler.postDelayed(pollRunnable, 1500)
    }

    fun stopPolling() {
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollCount++
            // Ask the page if the user is signed in. The response comes back
            // asynchronously via BxAndroid.onAuthState().
            webView.evaluateJavascript(
                """
                (function(){
                  try {
                    var u = window.xbcUser || null;
                    var isSignedIn = !!(u && u.isSignedIn);
                    var gamertag = (u && u.gamertag) ? u.gamertag : null;
                    var avatar = (u && u.gamerpic) ? u.gamerpic : null;
                    BxAndroid.onAuthState(JSON.stringify({isSignedIn: isSignedIn, gamertag: gamertag, avatar: avatar}));
                  } catch(e) {
                    BxAndroid.onAuthState(JSON.stringify({isSignedIn: false, error: String(e)}));
                  }
                })();
                """.trimIndent(),
                null
            )
            // Also refresh the settings cache from localStorage each poll.
            webView.evaluateJavascript(
                "(function(){ try { BxAndroid.onSettingsRead(localStorage.getItem('BetterXcloud') || '{}'); } catch(e){ BxAndroid.onSettingsRead('{}'); } })();",
                null
            )

            // Stop polling once we've seen a definite signed-in state.
            if (app.sessionState.value == BxApp.SessionState.SIGNED_IN) return
            // Safety: stop after 60 polls (~90s) and assume signed-out.
            if (pollCount > 60) {
                app.setSession(BxApp.SessionState.SIGNED_OUT)
                return
            }
            handler.postDelayed(this, 1500)
        }
    }

    // ------------------------------------------------------------------
    // JS callbacks — invoked by the page via BxAndroid.<method>(...).
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun onAuthState(json: String) {
        try {
            val o = JSONObject(json)
            val isSignedIn = o.optBoolean("isSignedIn", false)
            val gamertag = o.optString("gamertag", null) ?: null
            val avatar = o.optString("avatar", null) ?: null
            Log.i(logTag, "auth state: signedIn=$isSignedIn gamertag=$gamertag")
            if (isSignedIn) {
                app.setSession(BxApp.SessionState.SIGNED_IN, gamertag, avatar)
                // After sign-in, fetch the library once so it's ready when Home opens.
                fetchLibraryAndCache()
            }
        } catch (t: Throwable) {
            Log.e(logTag, "onAuthState parse failed", t)
        }
    }

    @JavascriptInterface
    fun onSettingsRead(json: String) {
        BxSettingsStore.update(json)
    }

    @JavascriptInterface
    fun onLibraryResult(json: String) {
        try {
            // Persist to in-memory cache; the Home view-model will pick it up via LiveData.
            val arr = JSONArray(json)
            val games = mutableListOf<XcloudGame>()
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                games.add(
                    XcloudGame(
                        id = g.optString("id"),
                        title = g.optString("title", "Unknown"),
                        imageUrl = g.optString("image", ""),
                        publisherName = g.optString("publisher", ""),
                        releaseDate = g.optString("releaseDate", ""),
                        categories = emptyList(),
                    )
                )
            }
            _libraryCache.postValue(games)
        } catch (t: Throwable) {
            Log.e(logTag, "onLibraryResult parse failed", t)
            _libraryCache.postValue(emptyList())
        }
    }

    @JavascriptInterface
    fun toast(message: String) {
        handler.post {
            android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.i(logTag, "page: $message")
    }

    @JavascriptInterface
    fun version(): String = BuildConfig.VERSION_NAME

    // ------------------------------------------------------------------
    // Active operations — invoked from Kotlin (not from the page).
    // ------------------------------------------------------------------

    private val _libraryCache = androidx.lifecycle.MutableLiveData<List<XcloudGame>>()
    val library: androidx.lifecycle.LiveData<List<XcloudGame>> = _libraryCache

    /**
     * Fetches the user's full Game Pass Cloud library by calling the catalog
     * sigls endpoint from inside the page context (so auth cookies apply).
     * Then fetches each title's details via the displaycatalog endpoint.
     */
    fun fetchLibraryAndCache() {
        // JavaScript that runs inside the page's fetch context. We use the
        // sigls endpoint to get IDs, then call the displaycatalog batch endpoint
        // to get titles + images in a single request.
        val js = """
            (async function(){
              try {
                const market = 'US';
                const lang = (navigator.language || 'en-US');
                const galleryId = '${BxConstants.Gallery.ALL}';
                const siglsUrl = `https://catalog.gamepass.com/sigls/v2?id=${'$'}{galleryId}&market=${'$'}{market}&language=${'$'}{lang}`;
                const siglsResp = await fetch(siglsUrl, {credentials: 'include'});
                const sigls = await siglsResp.json();
                // index 0 is metadata, skip it
                const ids = sigls.slice(1).map(s => s.id).filter(Boolean);
                if (!ids.length) {
                  BxAndroid.onLibraryResult('[]');
                  return;
                }
                // Batch fetch details — displaycatalog accepts multiple bigIds.
                const bigIdsParam = ids.map(id => 'bigId=' + encodeURIComponent(id)).join('&');
                const detailsUrl = 'https://displaycatalog.mp.microsoft.com/v7.0/products?' + bigIdsParam + '&market=' + market + '&language=' + lang + '&fieldsTemplate=details';
                const detailsResp = await fetch(detailsUrl, {credentials: 'include'});
                const detailsJson = await detailsResp.json();
                const products = (detailsJson.Products || []);
                const out = products.map(p => ({
                  id: p.ProductId || '',
                  title: p.Title || 'Unknown',
                  image: (p.Images || []).find(i => i.ImagePurpose === 'FeaturePromotionalArt')?.Uri
                       || (p.Images || []).find(i => i.ImagePurpose === 'BoxArt')?.Uri
                       || (p.Images || [])[0]?.Uri
                       || '',
                  publisher: p.PublisherName || '',
                  releaseDate: p.ReleaseDate || '',
                }));
                BxAndroid.onLibraryResult(JSON.stringify(out));
              } catch(e) {
                BxAndroid.log('fetchLibrary error: ' + String(e));
                BxAndroid.onLibraryResult('[]');
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Persists the current [BxSettings] to localStorage. Called by the Settings
     * activity when the user changes a setting.
     */
    fun persistSettings(settings: BxSettings) {
        val json = settings.toJsonString()
        // localStorage.setItem is synchronous — safe to do in evaluateJavascript.
        val escaped = JSONObject().put("v", json).toString()
        webView.evaluateJavascript(
            "localStorage.setItem('BetterXcloud', JSON.parse('$escaped').v);",
            null
        )
        BxSettingsStore.update(settings)
    }

    /**
     * Reads the current settings from localStorage (refreshes the cache).
     */
    fun refreshSettings() {
        webView.evaluateJavascript(
            "(function(){ try { BxAndroid.onSettingsRead(localStorage.getItem('BetterXcloud') || '{}'); } catch(e){ BxAndroid.onSettingsRead('{}'); } })();",
            null
        )
    }
}

/** Simple data class for a game in the library. */
data class XcloudGame(
    val id: String,
    val title: String,
    val imageUrl: String,
    val publisherName: String,
    val releaseDate: String,
    val categories: List<String> = emptyList(),
)
