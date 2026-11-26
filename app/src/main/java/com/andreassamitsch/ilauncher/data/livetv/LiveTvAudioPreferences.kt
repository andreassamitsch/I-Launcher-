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

enum class LiveTvAudioQuality(val displayName: String) {
    BestAvailable("Beste verfügbare"),
    SenderDefault("Senderstandard");

    companion object {
        fun fromStored(value: String?): LiveTvAudioQuality =
            entries.firstOrNull { it.name == value } ?: BestAvailable
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

    private val _audioQuality = MutableStateFlow(
        LiveTvAudioQuality.fromStored(preferences.getString("audio_quality", null)),
    )
    val audioQuality: StateFlow<LiveTvAudioQuality> = _audioQuality.asStateFlow()

    private val _preferSurround = MutableStateFlow(
        preferences.getBoolean("prefer_surround", true),
    )
    val preferSurround: StateFlow<Boolean> = _preferSurround.asStateFlow()

    fun setPreferredLanguage(value: LiveTvAudioLanguage) {
        preferences.edit().putString("preferred_language", value.name).apply()
        _preferredLanguage.value = value
    }

    fun setAvoidSpecialTracks(value: Boolean) {
        preferences.edit().putBoolean("avoid_special_tracks", value).apply()
        _avoidSpecialTracks.value = value
    }

    fun setAudioQuality(value: LiveTvAudioQuality) {
        preferences.edit().putString("audio_quality", value.name).apply()
        _audioQuality.value = value
    }

    fun setPreferSurround(value: Boolean) {
        preferences.edit().putBoolean("prefer_surround", value).apply()
        _preferSurround.value = value
    }
}
