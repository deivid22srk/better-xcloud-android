package com.betterxcloud.app

/**
 * Central place for all constants that bridge the native app and the
 * Better xCloud userscript running inside the WebView.
 */
object BxConstants {

    // ------------------------------------------------------------------
    // Xbox / xCloud URLs
    // ------------------------------------------------------------------
    const val XBOX_PLAY_URL = "https://www.xbox.com/play"

    /**
     * Desktop Edge on Windows UA — forces xbox.com to serve the full desktop
     * xCloud experience instead of the limited mobile web flow.
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36 Edg/124.0.2478.0"

    // ------------------------------------------------------------------
    // Game Pass Cloud gallery IDs (from redphx/better-xcloud enums)
    // ------------------------------------------------------------------
    object Gallery {
        const val ALL = "29a81209-df6f-41fd-a528-2ae6b91f719c"
        const val ALL_WITH_BYOG = "ce573635-7c18-4d0c-9d68-90b932393470"
        const val LEAVING_SOON = "393f05bf-e596-4ef6-9487-6d4fa0eab987"
        const val MOST_POPULAR = "e7590b22-e299-44db-ae22-25c61405454c"
        const val NATIVE_MKB = "8fa264dd-124f-4af3-97e8-596fcdf4b486"
        const val RECENTLY_ADDED = "44a55037-770f-4bbf-bde5-a9fa27dba1da"
        const val TOUCH = "9c86f07a-f3e8-45ad-82a0-a1f759597059"
    }

    /**
     * Catalog sigls endpoint — returns the list of title IDs in a given gallery.
     * Response is a JSON array: [ {id: "..."}, {id: "..."}, ... ]
     * Note: index 0 is metadata, real titles start at index 1.
     */
    fun catalogSiglsUrl(galleryId: String, market: String = "US", language: String = "en-US"): String =
        "https://catalog.gamepass.com/sigls/v2?id=$galleryId&market=$market&language=$language"

    /**
     * Display catalog endpoint — returns title details (title, image, publisher, etc.)
     * for one or more bigIds. POST with JSON body.
     */
    const val DISPLAY_CATALOG_URL = "https://displaycatalog.mp.microsoft.com/v7.0/products"

    // ------------------------------------------------------------------
    // LocalStorage keys used by the Better xCloud userscript
    // (from src/enums/pref-keys.ts)
    // ------------------------------------------------------------------
    object Storage {
        const val GLOBAL = "BetterXcloud"
        const val STREAM = "BetterXcloud.Stream"
        const val LOCALE = "BetterXcloud.Locale"
        const val USER_AGENT = "BetterXcloud.UserAgent"
    }

    /**
     * Global preference keys (from src/enums/pref-keys.ts → GlobalPref enum).
     * These are JSON object keys stored under localStorage[BetterXcloud].
     */
    object Pref {
        const val SERVER_REGION = "server.region"
        const val SERVER_BYPASS = "server.bypassRestriction"
        const val SERVER_IPV6 = "server.ipv6.prefer"

        const val STREAM_LOCALE = "stream.locale"
        const val STREAM_RESOLUTION = "stream.video.resolution"
        const val STREAM_CODEC = "stream.video.codecProfile"
        const val STREAM_BITRATE = "stream.video.maxBitrate"
        const val STREAM_COMBINE_AUDIO = "stream.video.combineAudio"
        const val STREAM_PREVENT_DROPS = "stream.video.preventResolutionDrops"

        const val TOUCH_MODE = "touchController.mode"
        const val TOUCH_AUTO_OFF = "touchController.autoOff"
        const val TOUCH_OPACITY = "touchController.opacity.default"
        const val TOUCH_STYLE_STANDARD = "touchController.style.standard"

        const val GAMEBAR_POSITION = "gameBar.position"

        const val NATIVE_MKB_MODE = "nativeMkb.mode"
        const val MKB_ENABLED = "mkb.enabled"
        const val MKB_HIDE_CURSOR = "mkb.cursor.hideIdle"

        const val SCREENSHOT_FILTERS = "screenshot.applyFilters"

        const val BLOCK_TRACKING = "block.tracking"
        const val BLOCK_FEATURES = "block.features"

        const val LOADING_ART = "loadingScreen.gameArt.show"
        const val LOADING_WAIT = "loadingScreen.waitTime.show"
        const val LOADING_ROCKET = "loadingScreen.rocket"

        const val UI_CONTROLLER_FRIENDLY = "ui.controllerFriendly"
        const val UI_LAYOUT = "ui.layout"
        const val UI_HIDE_SCROLLBAR = "ui.hideScrollbar"
        const val UI_SKIP_SPLASH = "ui.splashVideo.skip"
        const val UI_REDUCE_ANIMATIONS = "ui.reduceAnimations"
        const val UI_IMAGE_QUALITY = "ui.imageQuality"
        const val UI_THEME = "ui.theme"

        const val AUDIO_MIC_ON_PLAY = "audio.mic.onPlaying"
        const val AUDIO_VOLUME_BOOSTER = "audio.volume.booster.enabled"
    }

    /** Default values for prefs that should be safe to fall back to. */
    object PrefDefault {
        const val STREAM_RESOLUTION = "auto"
        const val STREAM_CODEC = "default"
        const val STREAM_BITRATE = 0
        const val SERVER_REGION = "default"
        const val SERVER_BYPASS = "off"
        const val TOUCH_MODE = "all"
        const val MKB_ENABLED = false
        const val UI_THEME = "auto"
    }

    // ------------------------------------------------------------------
    // Stream resolution values (from src/enums/pref-values.ts)
    // ------------------------------------------------------------------
    object Resolution {
        const val AUTO = "auto"
        const val P720 = "720p"
        const val P1080 = "1080p"
        const val P1600 = "1600p"
    }

    object CodecProfile {
        const val DEFAULT = "default"
        const val LOW = "low"
        const val NORMAL = "normal"
        const val HIGH = "high"
    }

    object TouchMode {
        const val ALL = "all"
        const val OFF = "off"
        const val DEFAULT = "default"
    }

    object GameBarPosition {
        const val LEFT = "left"
        const val RIGHT = "right"
        const val TOP = "top"
        const val BOTTOM = "bottom"
    }
}
