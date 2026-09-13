package com.andreassamitsch.ilauncher.data.openwebif

import android.content.Context

internal class OpenWebifPiconCache(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun currentGeneration(): Long = preferences.getLong(KEY_GENERATION, 0L)

    fun invalidate(): Long {
        val current = currentGeneration()
        val next = maxOf(System.currentTimeMillis(), current + 1L)
        preferences.edit()
            .putLong(KEY_GENERATION, next)
            .apply()
        return next
    }

    companion object {
        private const val PREFERENCES_NAME = "openwebif_picon_cache"
        private const val KEY_GENERATION = "generation"
    }
}
