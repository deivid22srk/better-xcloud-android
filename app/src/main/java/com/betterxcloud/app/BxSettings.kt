package com.betterxcloud.app

import android.util.Log
import org.json.JSONObject

/**
 * In-memory representation of the Better xCloud settings.
 *
 * Persistence lives in the WebView's localStorage (so the userscript picks it up
 * automatically). [BxSettingsStore] is the bridge that reads/writes those keys
 * via JavaScript evaluation on the (hidden) auth WebView.
 */
data class BxSettings(
    // Stream
    var streamResolution: String = BxConstants.PrefDefault.STREAM_RESOLUTION,
    var streamCodec: String = BxConstants.PrefDefault.STREAM_CODEC,
    var streamBitrate: Int = BxConstants.PrefDefault.STREAM_BITRATE,
    var streamCombineAudio: Boolean = false,
    var streamPreventDrops: Boolean = false,

    // Server / region
    var serverRegion: String = BxConstants.PrefDefault.SERVER_REGION,
    var serverBypass: String = BxConstants.PrefDefault.SERVER_BYPASS,
    var serverIpv6: Boolean = false,

    // Controller
    var touchMode: String = BxConstants.PrefDefault.TOUCH_MODE,
    var touchAutoOff: Boolean = false,
    var touchOpacity: Int = 100,
    var touchStyleStandard: String = "default",
    var gamebarPosition: String = BxConstants.GameBarPosition.RIGHT,
    var mkbEnabled: Boolean = BxConstants.PrefDefault.MKB_ENABLED,
    var nativeMkbMode: String = "off",

    // Interface
    var uiControllerFriendly: Boolean = false,
    var uiLayout: String = "default",
    var uiHideScrollbar: Boolean = false,
    var uiSkipSplash: Boolean = false,
    var uiReduceAnimations: Boolean = false,
    var uiImageQuality: String = "default",
    var uiTheme: String = BxConstants.PrefDefault.UI_THEME,

    // Audio
    var audioMicOnPlay: Boolean = false,
    var audioVolumeBooster: Boolean = false,

    // Loading screen
    var loadingArt: Boolean = true,
    var loadingWait: Boolean = true,
    var loadingRocket: String = "default",

    // Advanced
    var blockTracking: Boolean = true,
    var screenshotFilters: Boolean = true,
) {
    companion object {
        private const val TAG = "BxSettings"

        /** Parse a JSON object (the value of localStorage["BetterXcloud"]) into a BxSettings. */
        fun fromJson(json: String?): BxSettings {
            val s = BxSettings()
            if (json.isNullOrBlank()) return s
            return try {
                val o = JSONObject(json)
                s.apply {
                    streamResolution = o.optString(BxConstants.Pref.STREAM_RESOLUTION, streamResolution)
                    streamCodec = o.optString(BxConstants.Pref.STREAM_CODEC, streamCodec)
                    streamBitrate = o.optInt(BxConstants.Pref.STREAM_BITRATE, streamBitrate)
                    streamCombineAudio = o.optBoolean(BxConstants.Pref.STREAM_COMBINE_AUDIO, streamCombineAudio)
                    streamPreventDrops = o.optBoolean(BxConstants.Pref.STREAM_PREVENT_DROPS, streamPreventDrops)
                    serverRegion = o.optString(BxConstants.Pref.SERVER_REGION, serverRegion)
                    serverBypass = o.optString(BxConstants.Pref.SERVER_BYPASS, serverBypass)
                    serverIpv6 = o.optBoolean(BxConstants.Pref.SERVER_IPV6, serverIpv6)
                    touchMode = o.optString(BxConstants.Pref.TOUCH_MODE, touchMode)
                    touchAutoOff = o.optBoolean(BxConstants.Pref.TOUCH_AUTO_OFF, touchAutoOff)
                    touchOpacity = o.optInt(BxConstants.Pref.TOUCH_OPACITY, touchOpacity)
                    touchStyleStandard = o.optString(BxConstants.Pref.TOUCH_STYLE_STANDARD, touchStyleStandard)
                    gamebarPosition = o.optString(BxConstants.Pref.GAMEBAR_POSITION, gamebarPosition)
                    mkbEnabled = o.optBoolean(BxConstants.Pref.MKB_ENABLED, mkbEnabled)
                    nativeMkbMode = o.optString(BxConstants.Pref.NATIVE_MKB_MODE, nativeMkbMode)
                    uiControllerFriendly = o.optBoolean(BxConstants.Pref.UI_CONTROLLER_FRIENDLY, uiControllerFriendly)
                    uiLayout = o.optString(BxConstants.Pref.UI_LAYOUT, uiLayout)
                    uiHideScrollbar = o.optBoolean(BxConstants.Pref.UI_HIDE_SCROLLBAR, uiHideScrollbar)
                    uiSkipSplash = o.optBoolean(BxConstants.Pref.UI_SKIP_SPLASH, uiSkipSplash)
                    uiReduceAnimations = o.optBoolean(BxConstants.Pref.UI_REDUCE_ANIMATIONS, uiReduceAnimations)
                    uiImageQuality = o.optString(BxConstants.Pref.UI_IMAGE_QUALITY, uiImageQuality)
                    uiTheme = o.optString(BxConstants.Pref.UI_THEME, uiTheme)
                    audioMicOnPlay = o.optBoolean(BxConstants.Pref.AUDIO_MIC_ON_PLAY, audioMicOnPlay)
                    audioVolumeBooster = o.optBoolean(BxConstants.Pref.AUDIO_VOLUME_BOOSTER, audioVolumeBooster)
                    loadingArt = o.optBoolean(BxConstants.Pref.LOADING_ART, loadingArt)
                    loadingWait = o.optBoolean(BxConstants.Pref.LOADING_WAIT, loadingWait)
                    loadingRocket = o.optString(BxConstants.Pref.LOADING_ROCKET, loadingRocket)
                    blockTracking = o.optBoolean(BxConstants.Pref.BLOCK_TRACKING, blockTracking)
                    screenshotFilters = o.optBoolean(BxConstants.Pref.SCREENSHOT_FILTERS, screenshotFilters)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to parse BxSettings JSON, using defaults", t)
                s
            }
        }
    }

    /** Serialize back to the JSON format the userscript expects. */
    fun toJsonString(): String {
        val o = JSONObject()
        o.put(BxConstants.Pref.STREAM_RESOLUTION, streamResolution)
        o.put(BxConstants.Pref.STREAM_CODEC, streamCodec)
        o.put(BxConstants.Pref.STREAM_BITRATE, streamBitrate)
        o.put(BxConstants.Pref.STREAM_COMBINE_AUDIO, streamCombineAudio)
        o.put(BxConstants.Pref.STREAM_PREVENT_DROPS, streamPreventDrops)
        o.put(BxConstants.Pref.SERVER_REGION, serverRegion)
        o.put(BxConstants.Pref.SERVER_BYPASS, serverBypass)
        o.put(BxConstants.Pref.SERVER_IPV6, serverIpv6)
        o.put(BxConstants.Pref.TOUCH_MODE, touchMode)
        o.put(BxConstants.Pref.TOUCH_AUTO_OFF, touchAutoOff)
        o.put(BxConstants.Pref.TOUCH_OPACITY, touchOpacity)
        o.put(BxConstants.Pref.TOUCH_STYLE_STANDARD, touchStyleStandard)
        o.put(BxConstants.Pref.GAMEBAR_POSITION, gamebarPosition)
        o.put(BxConstants.Pref.MKB_ENABLED, mkbEnabled)
        o.put(BxConstants.Pref.NATIVE_MKB_MODE, nativeMkbMode)
        o.put(BxConstants.Pref.UI_CONTROLLER_FRIENDLY, uiControllerFriendly)
        o.put(BxConstants.Pref.UI_LAYOUT, uiLayout)
        o.put(BxConstants.Pref.UI_HIDE_SCROLLBAR, uiHideScrollbar)
        o.put(BxConstants.Pref.UI_SKIP_SPLASH, uiSkipSplash)
        o.put(BxConstants.Pref.UI_REDUCE_ANIMATIONS, uiReduceAnimations)
        o.put(BxConstants.Pref.UI_IMAGE_QUALITY, uiImageQuality)
        o.put(BxConstants.Pref.UI_THEME, uiTheme)
        o.put(BxConstants.Pref.AUDIO_MIC_ON_PLAY, audioMicOnPlay)
        o.put(BxConstants.Pref.AUDIO_VOLUME_BOOSTER, audioVolumeBooster)
        o.put(BxConstants.Pref.LOADING_ART, loadingArt)
        o.put(BxConstants.Pref.LOADING_WAIT, loadingWait)
        o.put(BxConstants.Pref.LOADING_ROCKET, loadingRocket)
        o.put(BxConstants.Pref.BLOCK_TRACKING, blockTracking)
        o.put(BxConstants.Pref.SCREENSHOT_FILTERS, screenshotFilters)
        return o.toString()
    }
}

/**
 * Singleton holder for the current settings + the WebView reference used to
 * persist them. The WebView is set once the auth activity creates it, and is
 * shared across the app via [BxApp].
 */
object BxSettingsStore {
    @Volatile
    var current: BxSettings = BxSettings()
        private set

    fun update(json: String?) {
        current = BxSettings.fromJson(json)
    }

    fun update(settings: BxSettings) {
        current = settings
    }
}
