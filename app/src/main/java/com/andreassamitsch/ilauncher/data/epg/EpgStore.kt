package com.andreassamitsch.ilauncher.data.epg

import android.content.Context
import com.google.gson.Gson

internal class EpgStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val gson = Gson()

    fun sourceUrl(): String = preferences.getString(KEY_SOURCE_URL, DEFAULT_M3U_URL)
        ?.takeIf(String::isNotBlank)
        ?: DEFAULT_M3U_URL

    fun saveSourceUrl(url: String) {
        preferences.edit()
            .putString(KEY_SOURCE_URL, url)
            .remove(KEY_SOURCE_SNAPSHOT)
            .remove(KEY_XMLTV_UPDATED_AT)
            .remove(KEY_XMLTV_CHANNEL_IDS)
            .apply()
    }

    fun loadSourceSnapshot(): EpgSourceSnapshot? {
        val json = preferences.getString(KEY_SOURCE_SNAPSHOT, null) ?: return null
        val snapshot = runCatching {
            gson.fromJson(json, EpgSourceSnapshot::class.java)
        }.getOrNull() ?: return null
        return snapshot.takeIf { it.sourceUrl == sourceUrl() }
    }

    fun saveSourceSnapshot(snapshot: EpgSourceSnapshot) {
        preferences.edit()
            .putString(KEY_SOURCE_SNAPSHOT, gson.toJson(snapshot))
            .apply()
    }

    fun xmlTvUpdatedAtUtcMillis(): Long? = preferences
        .getLong(KEY_XMLTV_UPDATED_AT, 0L)
        .takeIf { it > 0L }

    fun saveXmlTvUpdatedAtUtcMillis(value: Long) {
        preferences.edit().putLong(KEY_XMLTV_UPDATED_AT, value).apply()
    }

    fun xmlTvChannelIds(): Set<String> {
        val json = preferences.getString(KEY_XMLTV_CHANNEL_IDS, null) ?: return emptySet()
        return runCatching {
            gson.fromJson(json, Array<String>::class.java).toSet()
        }.getOrDefault(emptySet())
    }

    fun saveXmlTvChannelIds(ids: Set<String>) {
        preferences.edit()
            .putString(KEY_XMLTV_CHANNEL_IDS, gson.toJson(ids.toTypedArray()))
            .apply()
    }

    companion object {
        const val DEFAULT_M3U_URL = "https://riedl-dach.at/tv.m3u"
        private const val PREFERENCES_NAME = "epg_source"
        private const val KEY_SOURCE_URL = "source_url"
        private const val KEY_SOURCE_SNAPSHOT = "source_snapshot"
        private const val KEY_XMLTV_UPDATED_AT = "xmltv_updated_at"
        private const val KEY_XMLTV_CHANNEL_IDS = "xmltv_channel_ids"
    }
}

internal data class EpgSourceSnapshot(
    val sourceUrl: String,
    val epgUrl: String,
    val channels: List<EpgSourceChannel>,
    val updatedAtUtcMillis: Long,
)
