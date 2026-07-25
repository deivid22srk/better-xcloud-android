package com.betterxcloud.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class XcloudBridge(private val app: BxApp, private val webView: WebView) {

    private val handler = Handler(Looper.getMainLooper())
    private val bgHandler = Handler(Looper.getMainLooper())
    private val logTag = "BxAndroid"

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
            webView.evaluateJavascript(
                "(function(){ try { BxAndroid.onSettingsRead(localStorage.getItem('BetterXcloud') || '{}'); } catch(e){ BxAndroid.onSettingsRead('{}'); } })();",
                null
            )

            if (app.sessionState.value == BxApp.SessionState.SIGNED_IN) return
            if (pollCount > 60) {
                app.setSession(BxApp.SessionState.SIGNED_OUT)
                return
            }
            handler.postDelayed(this, 1500)
        }
    }

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

    private val _libraryCache = androidx.lifecycle.MutableLiveData<List<XcloudGame>>()
    val library: androidx.lifecycle.LiveData<List<XcloudGame>> = _libraryCache

    fun fetchLibraryAndCache() {
        bgHandler.post {
            Thread {
                try {
                    val cookies = CookieManager.getInstance().getCookie("https://www.xbox.com") ?: ""
                    val market = "US"
                    val lang = "en-US"
                    val galleryId = BxConstants.Gallery.ALL

                    val siglsUrl = "https://catalog.gamepass.com/sigls/v2?id=$galleryId&market=$market&language=$lang"
                    val siglsJson = httpGet(siglsUrl, cookies)
                    val siglsArray = JSONArray(siglsJson)
                    val ids = mutableListOf<String>()
                    for (i in 1 until siglsArray.length()) {
                        val obj = siglsArray.optJSONObject(i)
                        val id = obj?.optString("id")
                        if (id != null) ids.add(id)
                    }

                    if (ids.isEmpty()) {
                        handler.post { _libraryCache.postValue(emptyList()) }
                        return@Thread
                    }

                    val bigIdsParam = ids.joinToString("&") { "bigId=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                    val detailsUrl = "${BxConstants.DISPLAY_CATALOG_URL}?$bigIdsParam&market=$market&language=$lang&fieldsTemplate=details"
                    val detailsJson = httpGet(detailsUrl, cookies)
                    val detailsObj = JSONObject(detailsJson)
                    val products = detailsObj.optJSONArray("Products") ?: JSONArray()

                    val games = mutableListOf<XcloudGame>()
                    for (i in 0 until products.length()) {
                        val p = products.optJSONObject(i) ?: continue
                        val images = p.optJSONArray("Images") ?: JSONArray()
                        var imageUrl = ""
                        for (j in 0 until images.length()) {
                            val img = images.optJSONObject(j) ?: continue
                            val purpose = img.optString("ImagePurpose")
                            if (purpose == "FeaturePromotionalArt" || purpose == "BoxArt") {
                                imageUrl = img.optString("Uri", "")
                                break
                            }
                            if (j == 0) imageUrl = img.optString("Uri", "")
                        }
                        games.add(XcloudGame(
                            id = p.optString("ProductId", ""),
                            title = p.optString("Title", "Unknown"),
                            imageUrl = imageUrl,
                            publisherName = p.optString("PublisherName", ""),
                            releaseDate = p.optString("ReleaseDate", ""),
                        ))
                    }

                    handler.post { _libraryCache.postValue(games) }
                } catch (t: Throwable) {
                    Log.e(logTag, "fetchLibrary error", t)
                    handler.post { _libraryCache.postValue(emptyList()) }
                }
            }.start()
        }
    }

    private fun httpGet(urlString: String, cookies: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Cookie", cookies)
        conn.setRequestProperty("User-Agent", BxConstants.DESKTOP_UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    /**
     * Persists the current [BxSettings] to localStorage. Called by the Settings
     * activity when the user changes a setting.
     */
    fun persistSettings(settings: BxSettings) {
        val json = settings.toJsonString()
        val escaped = JSONObject().put("v", json).toString()
        handler.post {
            webView.evaluateJavascript(
                "localStorage.setItem('BetterXcloud', JSON.parse('$escaped').v);",
                null
            )
        }
        BxSettingsStore.update(settings)
    }

    fun refreshSettings() {
        handler.post {
            webView.evaluateJavascript(
                "(function(){ try { BxAndroid.onSettingsRead(localStorage.getItem('BetterXcloud') || '{}'); } catch(e){ BxAndroid.onSettingsRead('{}'); } })();",
                null
            )
        }
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
