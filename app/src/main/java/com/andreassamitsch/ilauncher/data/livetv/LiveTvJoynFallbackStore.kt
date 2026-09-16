package com.andreassamitsch.ilauncher.data.livetv

import android.content.Context

/** Small local preference for the in-player Joyn fallback automation switch. */
internal class LiveTvJoynFallbackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoFallbackEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_FALLBACK_ENABLED, true)

    fun setAutoFallbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FALLBACK_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "live_tv_joyn_fallback"
        private const val KEY_AUTO_FALLBACK_ENABLED = "auto_fallback_enabled"
    }
}
