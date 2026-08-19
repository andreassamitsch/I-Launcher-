package com.andreassamitsch.ilauncher.data.tv

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreviewChannelPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _hiddenChannelIds = MutableStateFlow(loadSet(KEY_HIDDEN_CHANNEL_IDS))
    val hiddenChannelIds: StateFlow<Set<String>> = _hiddenChannelIds.asStateFlow()

    // Opt-in by design. App channels remain provider-original unless the user explicitly enables TMDB.
    private val _tmdbEnrichedChannelIds = MutableStateFlow(loadSet(KEY_TMDB_ENRICHED_CHANNEL_IDS))
    val tmdbEnrichedChannelIds: StateFlow<Set<String>> = _tmdbEnrichedChannelIds.asStateFlow()

    fun setVisible(channelId: String, visible: Boolean) {
        val updated = _hiddenChannelIds.value.toMutableSet()
        if (visible) {
            updated.remove(channelId)
        } else {
            updated.add(channelId)
        }
        saveSet(KEY_HIDDEN_CHANNEL_IDS, updated)
        _hiddenChannelIds.value = updated.toSet()
    }

    fun showAll() {
        preferences.edit().remove(KEY_HIDDEN_CHANNEL_IDS).apply()
        _hiddenChannelIds.value = emptySet()
    }

    fun setTmdbEnrichmentEnabled(channelId: String, enabled: Boolean) {
        val updated = _tmdbEnrichedChannelIds.value.toMutableSet()
        if (enabled) {
            updated.add(channelId)
        } else {
            updated.remove(channelId)
        }
        saveSet(KEY_TMDB_ENRICHED_CHANNEL_IDS, updated)
        _tmdbEnrichedChannelIds.value = updated.toSet()
    }

    private fun saveSet(key: String, values: Set<String>) {
        preferences.edit().putStringSet(key, values.toSet()).apply()
    }

    private fun loadSet(key: String): Set<String> =
        preferences.getStringSet(key, emptySet())?.toSet().orEmpty()

    private companion object {
        const val PREFS_NAME = "preview_channels"
        const val KEY_HIDDEN_CHANNEL_IDS = "hidden_channel_ids"
        const val KEY_TMDB_ENRICHED_CHANNEL_IDS = "tmdb_enriched_channel_ids"
    }
}
