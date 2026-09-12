package com.andreassamitsch.joyntv

import android.content.Context

/**
 * Learns which live channels need the media path itself routed through Mysterium.
 *
 * The user's configured proxy mode stays untouched. In CONTROL_ONLY mode we temporarily promote
 * the process route to all-traffic only while a known/problematic channel is playing. Once the
 * player is closed (or the fallback fails), the saved API/Token routing mode is restored.
 */
internal class JoynStreamProxyFallback(context: Context) {
    private val appContext = context.applicationContext
    private val proxySettings = JoynProxySettings(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Restores the user's saved route and, for a previously learned channel, enables temporary
     * full-proxy before Media3 opens the DASH manifest.
     *
     * @return true when the media request should currently be considered full-proxy routed.
     */
    fun prepareLiveChannel(channelId: String): Boolean {
        proxySettings.restoreSavedRouting()
        val base = proxySettings.current()
        if (!base.isUsable) return false
        if (base.allTraffic) return true
        if (!base.isMysterium || !needsFullProxy(channelId)) return false
        return proxySettings.enableTemporaryAllTraffic()
    }

    /** Tries full-proxy with the currently active Mysterium lease without changing saved settings. */
    fun tryTemporaryFullProxy(): Boolean {
        val base = proxySettings.current()
        if (!base.isUsable || !base.isMysterium || base.allTraffic) return false
        return proxySettings.enableTemporaryAllTraffic()
    }

    /** Remember only after Media3 actually reaches READY through the fallback route. */
    fun confirmFullProxyRequired(channelId: String) {
        if (channelId.isBlank()) return
        val learned = prefs.getStringSet(KEY_CHANNELS, emptySet()).orEmpty().toMutableSet()
        if (learned.add(channelId)) {
            prefs.edit().putStringSet(KEY_CHANNELS, learned).apply()
        }
    }

    fun restoreSavedRouting() {
        proxySettings.restoreSavedRouting()
    }

    private fun needsFullProxy(channelId: String): Boolean =
        prefs.getStringSet(KEY_CHANNELS, emptySet()).orEmpty().contains(channelId)

    companion object {
        private const val PREFS_NAME = "joyn_stream_proxy_fallback"
        private const val KEY_CHANNELS = "full_proxy_live_channels"
    }
}
