package com.betterxcloud.app

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

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

                    val bigIdsParam = ids.joinToString("&") { "bigIds=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                    val detailsUrl = "${BxConstants.DISPLAY_CATALOG_URL}?$bigIdsParam&market=$market&languages=$lang&fieldsTemplate=details"
                    val detailsJson = httpGet(detailsUrl, cookies)
                    val detailsObj = JSONObject(detailsJson)
                    val products = detailsObj.optJSONArray("Products") ?: JSONArray()

                    val games = mutableListOf<XcloudGame>()
                    for (i in 0 until products.length()) {
                        val p = products.optJSONObject(i) ?: continue
                        val lp = p.optJSONArray("LocalizedProperties")?.optJSONObject(0) ?: continue
                        val images = lp.optJSONArray("Images") ?: JSONArray()
                        var imageUrl = ""
                        for (j in 0 until images.length()) {
                            val img = images.optJSONObject(j) ?: continue
                            val purpose = img.optString("ImagePurpose")
                            if (purpose == "Poster" || purpose == "BoxArt" || purpose == "SuperHeroArt") {
                                imageUrl = img.optString("Uri", "").removePrefix("//").let { "https://$it" }
                                break
                            }
                            if (j == 0) imageUrl = img.optString("Uri", "").removePrefix("//").let { "https://$it" }
                        }
                        val title = lp.optString("ProductTitle", "")
                        if (title.isBlank()) continue
                        games.add(XcloudGame(
                            id = p.optString("ProductId", ""),
                            title = title,
                            imageUrl = imageUrl,
                            publisherName = lp.optString("PublisherName", ""),
                            releaseDate = p.optString("LastModifiedDate", ""),
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

    // ------------------------------------------------------------------
    // Modern library fetch (play.xbox.com flow) — classifies each game as
    // GAME_PASS, PURCHASED, BOTH, or NOT_OWNED.
    //
    // Strategy (4 steps, see docs/owned-games-analysis-v2.md):
    //   1. Extract XSTS token mp.microsoft.com from CookieManager
    //   2. GET catalog.gamepass.com/sigls/v2?id=ALL_WITH_BYOG -> 3403 BigIds
    //   3. POST catalog.gamepass.com/v3/products?hydration=BaysideLowTopaz0
    //      in batches of 100 -> metadata + isGamePass flag
    //   4. POST emerald.xboxservices.com/xboxcomfd/entitlements/bulk
    //      -> isOwned flag (paginated via continuationToken)
    // ------------------------------------------------------------------

    fun fetchLibraryAndClassify() {
        bgHandler.post {
            Thread {
                try {
                    val cookies = CookieManager.getInstance().getCookie("https://www.xbox.com") ?: ""
                    if (cookies.isBlank()) {
                        Log.w(logTag, "fetchLibraryAndClassify: no cookies — not signed in?")
                        handler.post { _libraryCache.postValue(emptyList()) }
                        return@Thread
                    }
                    val msCv = generateMsCv()

                    // ─── ETAPA 1: extrair token mp.microsoft.com ───────────────
                    val (mpUhs, mpTok) = extractXstsToken(BxConstants.RelyingParty.MS_STORE, cookies)
                    if (mpUhs.isEmpty() || mpTok.isEmpty()) {
                        Log.w(logTag, "No mp.microsoft.com XSTS token — falling back to legacy fetch")
                        // Fall back to the legacy Game-Pass-only fetch so the UI
                        // still works even if the user is not fully signed in to
                        // the Microsoft Store audience.
                        fetchLibraryAndCache()
                        return@Thread
                    }

                    // ─── ETAPA 2: catálogo cloud (todos os BigIds jogáveis) ────
                    // ALL_WITH_BYOG = Game Pass + BYOG (Bring Your Own Game) =
                    // 3403 títulos cloud. Substitui o ALL antigo (571, GP only).
                    val siglsUrl = BxConstants.catalogSiglsUrl(BxConstants.Gallery.ALL_WITH_BYOG)
                    val siglsJson = httpGet(siglsUrl, cookies)
                    val siglsArr = JSONArray(siglsJson)
                    val allBigIds = mutableListOf<String>()
                    for (i in 1 until siglsArr.length()) {  // index 0 = metadata
                        siglsArr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let {
                            allBigIds.add(it)
                        }
                    }
                    Log.i(logTag, "Catalog cloud (ALL_WITH_BYOG): ${allBigIds.size} BigIds")

                    if (allBigIds.isEmpty()) {
                        handler.post { _libraryCache.postValue(emptyList()) }
                        return@Thread
                    }

                    // ─── ETAPA 3: metadados em batch via catalog v3 ────────────
                    // POST with body {"Products": [bigId, ...]} in batches of 100.
                    // Each product reveals: title, publisher, keyArts (cover),
                    // xcloud.cloudId, subscriptionDates (Game Pass flag).
                    val productMeta = mutableMapOf<String, ProductInfo>()
                    for (batch in allBigIds.chunked(100)) {
                        try {
                            val v3Url = "${BxConstants.CATALOG_V3_PRODUCTS_URL}" +
                                    "?market=US&language=en-US" +
                                    "&hydration=${URLEncoder.encode(BxConstants.HYDRATION_DEFAULT, "UTF-8")}"
                            val body = JSONObject().put("Products", JSONArray(batch)).toString()
                            val resp = httpPost(v3Url, body, cookies, mapOf(
                                "MS-CV" to msCv,
                                "Calling-App-Name" to "PlayXbox",
                                "Calling-App-Version" to "1.0.0",
                            ))
                            val products = JSONObject(resp).optJSONObject("Products") ?: JSONObject()
                            for (id in batch) {
                                val p = products.optJSONObject(id) ?: continue
                                val title = p.optString("title").takeIf { it.isNotEmpty() } ?: continue
                                val subDates = p.optJSONObject("subscriptionDates")
                                val isGamePass = subDates != null && subDates.length() > 0
                                val xcloud = p.optJSONObject("xcloud")
                                val cloudId = xcloud?.optString("cloudId") ?: ""
                                val keyArts = p.optJSONArray("keyArts") ?: JSONArray()
                                var imageUrl = ""
                                // Prefer Poster (purpose=6), fall back to first
                                for (j in 0 until keyArts.length()) {
                                    val art = keyArts.optJSONObject(j) ?: continue
                                    if (art.optInt("imagePurpose") == BxConstants.ImagePurpose.POSTER) {
                                        imageUrl = art.optString("uri", "")
                                        break
                                    }
                                    if (imageUrl.isEmpty()) {
                                        imageUrl = art.optString("uri", "")
                                    }
                                }
                                productMeta[id] = ProductInfo(
                                    bigId = id,
                                    title = title,
                                    publisher = p.optString("publisherName", ""),
                                    imageUrl = imageUrl,
                                    cloudId = cloudId,
                                    isGamePass = isGamePass,
                                    releaseDate = p.optString("originalReleaseDate", ""),
                                )
                            }
                        } catch (t: Throwable) {
                            Log.w(logTag, "v3/products batch failed (${batch.size} ids): ${t.message}")
                        }
                    }
                    Log.i(logTag, "Metadata fetched for ${productMeta.size}/${allBigIds.size} products")

                    // ─── ETAPA 4: entitlements bulk — quais o usuário comprou ──
                    // Paginated via continuationToken (pageSize=50 per request).
                    val ownedIds = mutableSetOf<String>()
                    var continuationToken: String? = null
                    var pageCount = 0
                    do {
                        try {
                            val baseEntUrl = "${BxConstants.EMERALD_BASE_URL}${BxConstants.ENTITLEMENTS_BULK_PATH}" +
                                    "?locale=en-US&getRecurrenceInfo=true&pageSize=50"
                            val entUrl = if (continuationToken != null) {
                                "$baseEntUrl&continuationToken=${URLEncoder.encode(continuationToken, "UTF-8")}"
                            } else baseEntUrl
                            val body = JSONObject().put("ProductIds", JSONArray(allBigIds)).toString()
                            val resp = httpPost(entUrl, body, cookies, mapOf(
                                "Authorization" to "XBL3.0 x=$mpUhs;$mpTok",
                                "X-MS-Api-Version" to "1.0",
                                "MS-CV" to msCv,
                                "Calling-App-Name" to "PlayXbox",
                            ))
                            val d = JSONObject(resp)
                            val ents = d.optJSONObject("entitlements") ?: JSONObject()
                            for (key in ents.keys()) {
                                val ent = ents.optJSONObject(key) ?: continue
                                if (ent.optBoolean("isOwned", false) &&
                                    ent.optString("status", "Active") == "Active") {
                                    ownedIds.add(key)
                                }
                            }
                            continuationToken = d.optString("continuationToken", null)
                            pageCount++
                            if (pageCount > 100) {
                                Log.w(logTag, "entitlements: too many pages, breaking")
                                break
                            }
                        } catch (t: Throwable) {
                            Log.w(logTag, "entitlements/bulk page $pageCount failed: ${t.message}")
                            break
                        }
                    } while (continuationToken != null && continuationToken.isNotEmpty())
                    Log.i(logTag, "User owns ${ownedIds.size} products (over $pageCount pages)")

                    // ─── CLASSIFICAÇÃO + montagem da lista final ───────────────
                    val games = productMeta.values.map { p ->
                        val ownership = when {
                            ownedIds.contains(p.bigId) && p.isGamePass -> Ownership.BOTH
                            ownedIds.contains(p.bigId) -> Ownership.PURCHASED
                            p.isGamePass -> Ownership.GAME_PASS
                            else -> Ownership.NOT_OWNED
                        }
                        XcloudGame(
                            id = p.bigId,
                            title = p.title,
                            imageUrl = p.imageUrl,
                            publisherName = p.publisher,
                            releaseDate = p.releaseDate,
                            ownership = ownership,
                            cloudEnabled = p.cloudId.isNotEmpty(),
                            cloudId = p.cloudId,
                        )
                    }.filter { it.ownership != Ownership.NOT_OWNED }
                        .sortedWith(compareBy<XcloudGame> { it.ownership.ordinal }
                            .thenBy { it.title.lowercase() })

                    Log.i(logTag, "Final library: ${games.size} games " +
                            "(GP=${games.count { it.ownership == Ownership.GAME_PASS }}, " +
                            "Purchased=${games.count { it.ownership == Ownership.PURCHASED }}, " +
                            "Both=${games.count { it.ownership == Ownership.BOTH }})")

                    handler.post { _libraryCache.postValue(games) }
                } catch (t: Throwable) {
                    Log.e(logTag, "fetchLibraryAndClassify error", t)
                    // Fallback to legacy on catastrophic failure
                    try { fetchLibraryAndCache() } catch (_: Throwable) {}
                    handler.post { _libraryCache.postValue(emptyList()) }
                }
            }.start()
        }
    }

    /** Internal helper model — parsed from catalog v3 response. */
    private data class ProductInfo(
        val bigId: String,
        val title: String,
        val publisher: String,
        val imageUrl: String,
        val cloudId: String,
        val isGamePass: Boolean,
        val releaseDate: String,
    )

    /**
     * Generates a Correlation Vector (MS-CV) in the Microsoft format:
     * `<22-char-base64url>.0`. Required by emerald.xboxservices.com and
     * catalog.gamepass.com/v3 endpoints.
     */
    private fun generateMsCv(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val base64 = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        ).take(22)
        return "$base64.0"
    }

    /**
     * Extracts (userHash, token) from the XBXXtkhttp://<audience>/ cookie.
     *
     * The XBXXtk* cookies contain XSTS tokens JWE-encrypted, scoped per
     * relying party (audience). Each cookie value is URL-encoded JSON:
     *   {"identityType":"XToken","tokenData":{"token":"...","userHash":"..."}}
     *
     * Cookie names may or may not have a trailing slash — we try both.
     */
    private fun extractXstsToken(audience: String, cookies: String): Pair<String, String> {
        // Cookie name candidates: with and without trailing slash
        val candidates = setOf(
            "XBXXtk" + URLEncoder.encode("http://$audience/", "UTF-8"),
            "XBXXtk" + URLEncoder.encode("http://$audience", "UTF-8"),
            "XBXXtk" + URLEncoder.encode("https://$audience/", "UTF-8"),
            "XBXXtk" + URLEncoder.encode("https://$audience", "UTF-8"),
        )
        for (cname in candidates) {
            // Match "cname=value" up to next ";" or end
            val pattern = Regex("${Regex.escape(cname)}=([^;]+)")
            val match = pattern.find(cookies) ?: continue
            val rawValue = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
            try {
                val obj = JSONObject(rawValue)
                val td = obj.optJSONObject("tokenData") ?: continue
                val uhs = td.optString("userHash", "")
                val tok = td.optString("token", "")
                if (uhs.isNotEmpty() && tok.isNotEmpty()) {
                    return uhs to tok
                }
            } catch (e: Exception) {
                Log.w(logTag, "Failed to parse XSTS token for $audience: ${e.message}")
            }
        }
        return "" to ""
    }

    /** POST JSON helper with extra headers. */
    private fun httpPost(
        urlString: String,
        body: String,
        cookies: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        Log.d(logTag, "httpPost: $urlString  body=${body.take(120)}...")
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Cookie", cookies)
        conn.setRequestProperty("User-Agent", BxConstants.DESKTOP_UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        conn.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in extraHeaders) conn.setRequestProperty(k, v)
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        Log.d(logTag, "httpPost response: $code")
        if (code in 200..299) {
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        }
        val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        Log.e(logTag, "httpPost error $code: ${errBody.take(500)}")
        throw java.io.IOException("$code: $urlString — ${errBody.take(200)}")
    }

    private fun httpGet(urlString: String, cookies: String): String {
        Log.d(logTag, "httpGet: $urlString")
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Cookie", cookies)
        conn.setRequestProperty("User-Agent", BxConstants.DESKTOP_UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val code = conn.responseCode
        Log.d(logTag, "httpGet response: $code")
        if (code in 200..299) {
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        }
        val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
        Log.e(logTag, "httpGet error $code: $errorBody")
        throw java.io.FileNotFoundException("$code: $urlString")
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
    /** How the user has access to this game. */
    val ownership: Ownership = Ownership.GAME_PASS,
    /** True if the game supports cloud streaming (has xcloud.cloudId). */
    val cloudEnabled: Boolean = true,
    /** Cloud ID used to launch streaming via xbox.com/play/launch/<cloudId>. */
    val cloudId: String = "",
)

/**
 * How the user has access to a given game in the library.
 *
 * Classification logic (see fetchLibraryAndClassify in XcloudBridge.kt):
 *   - isOwned (entitlements/bulk returned isOwned=true)
 *   - isGamePass (catalog v3 returned subscriptionDates with a Game Pass SKU)
 *
 *   isOwned && isGamePass → BOTH
 *   isOwned && !isGamePass → PURCHASED
 *   !isOwned && isGamePass → GAME_PASS
 *   !isOwned && !isGamePass → NOT_OWNED (filtered out before display)
 */
enum class Ownership(val label: String) {
    /** Game included in Game Pass (not purchased). */
    GAME_PASS("Game Pass"),
    /** Game purchased by the user (not in Game Pass). */
    PURCHASED("Comprado"),
    /** Purchased AND also in Game Pass. */
    BOTH("Comprado + GP"),
    /** Not owned — should be filtered out before display. */
    NOT_OWNED("Não possuí"),
}
