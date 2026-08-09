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

    private val _hiddenChannelIds = MutableStateFlow(loadHiddenChannelIds())
    val hiddenChannelIds: StateFlow<Set<String>> = _hiddenChannelIds.asStateFlow()

    fun setVisible(channelId: String, visible: Boolean) {
        val updated = _hiddenChannelIds.value.toMutableSet()
        if (visible) {
            updated.remove(channelId)
        } else {
            updated.add(channelId)
        }
        preferences.edit().putStringSet(KEY_HIDDEN_CHANNEL_IDS, updated).apply()
        _hiddenChannelIds.value = updated.toSet()
    }

    fun showAll() {
        preferences.edit().remove(KEY_HIDDEN_CHANNEL_IDS).apply()
        _hiddenChannelIds.value = emptySet()
    }

    private fun loadHiddenChannelIds(): Set<String> =
        preferences.getStringSet(KEY_HIDDEN_CHANNEL_IDS, emptySet())?.toSet().orEmpty()

    private companion object {
        const val PREFS_NAME = "preview_channels"
        const val KEY_HIDDEN_CHANNEL_IDS = "hidden_channel_ids"
    }
}
