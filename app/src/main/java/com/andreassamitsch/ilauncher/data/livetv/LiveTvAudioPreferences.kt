package com.andreassamitsch.ilauncher.data.livetv

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LiveTvAudioLanguage(val displayName: String, val languageTag: String?) {
    German("Deutsch", "de"),
    English("Englisch", "en"),
    Automatic("Automatisch", null);

    companion object {
        fun fromStored(value: String?): LiveTvAudioLanguage =
            entries.firstOrNull { it.name == value } ?: German
    }
}

/** Global playback defaults. Per-stream manual track changes are deliberately not persisted. */
class LiveTvAudioPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "i_launcher_live_tv_audio",
        Context.MODE_PRIVATE,
    )

    private val _preferredLanguage = MutableStateFlow(
        LiveTvAudioLanguage.fromStored(preferences.getString("preferred_language", null)),
    )
    val preferredLanguage: StateFlow<LiveTvAudioLanguage> = _preferredLanguage.asStateFlow()

    private val _avoidSpecialTracks = MutableStateFlow(
        preferences.getBoolean("avoid_special_tracks", true),
    )
    val avoidSpecialTracks: StateFlow<Boolean> = _avoidSpecialTracks.asStateFlow()

    fun setPreferredLanguage(value: LiveTvAudioLanguage) {
        preferences.edit().putString("preferred_language", value.name).apply()
        _preferredLanguage.value = value
    }

    fun setAvoidSpecialTracks(value: Boolean) {
        preferences.edit().putBoolean("avoid_special_tracks", value).apply()
        _avoidSpecialTracks.value = value
    }
}
