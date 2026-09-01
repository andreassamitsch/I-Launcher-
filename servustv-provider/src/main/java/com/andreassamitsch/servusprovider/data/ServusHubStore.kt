package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.google.gson.reflect.TypeToken

class ServusHubStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val categoryListType = object : TypeToken<List<ServusCategory>>() {}.type
    private val liveListType = object : TypeToken<List<ServusLiveChannel>>() {}.type

    fun loadCategories(): List<ServusCategory> {
        val categories = loadList<ServusCategory>(KEY_CATEGORIES, categoryListType)
        if (categories.isEmpty() || preferences.getInt(KEY_TIME_SCHEMA, 1) >= CURRENT_TIME_SCHEMA) {
            return categories
        }

        // dev.2 could store broadcast/title times as publishedAtMillis. They cannot be distinguished
        // reliably from a true sunrise timestamp afterwards, so discard them once rather than keep
        // displaying a known-wrong publication time. The next network refresh repopulates only
        // trustworthy source availability timestamps.
        val migrated = categories.map { category ->
            category.copy(
                shows = category.shows.map { show ->
                    show.copy(
                        episodes = show.episodes.map { episode ->
                            episode.copy(publishedAtMillis = null)
                        },
                    )
                },
            )
        }
        preferences.edit()
            .putString(KEY_CATEGORIES, ServusNetwork.gson.toJson(migrated))
            .putInt(KEY_TIME_SCHEMA, CURRENT_TIME_SCHEMA)
            .apply()
        return migrated
    }

    fun loadLiveChannels(): List<ServusLiveChannel> = loadList(KEY_LIVE_CHANNELS, liveListType)

    fun saveCatalog(categories: List<ServusCategory>, refreshedAtMillis: Long) {
        preferences.edit()
            .putString(KEY_CATEGORIES, ServusNetwork.gson.toJson(categories))
            .putLong(KEY_CATALOG_SUCCESS, refreshedAtMillis)
            .putInt(KEY_TIME_SCHEMA, CURRENT_TIME_SCHEMA)
            .apply()
    }

    fun saveCatalogDiagnostic(message: String) {
        preferences.edit()
            .putString(KEY_CATALOG_DIAGNOSTIC, ServusCatalogDiagnosticBuilder.sanitize(message))
            .apply()
    }

    fun catalogDiagnostic(): String? = preferences.getString(KEY_CATALOG_DIAGNOSTIC, null)
        ?.takeIf { it.isNotBlank() }

    fun saveLiveChannels(channels: List<ServusLiveChannel>, refreshedAtMillis: Long) {
        preferences.edit()
            .putString(KEY_LIVE_CHANNELS, ServusNetwork.gson.toJson(channels))
            .putLong(KEY_LIVE_SUCCESS, refreshedAtMillis)
            .apply()
    }

    fun catalogLastSuccessMillis(): Long = preferences.getLong(KEY_CATALOG_SUCCESS, 0L)

    fun liveLastSuccessMillis(): Long = preferences.getLong(KEY_LIVE_SUCCESS, 0L)

    fun findShow(showId: String): ServusShow? = loadCategories()
        .asSequence()
        .flatMap { it.shows.asSequence() }
        .firstOrNull { it.id == showId }

    fun findLiveChannel(channelId: String): ServusLiveChannel? = loadLiveChannels()
        .firstOrNull { it.id == channelId }

    private fun <T> loadList(key: String, type: java.lang.reflect.Type): List<T> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            ServusNetwork.gson.fromJson<List<T>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_NAME = "servus_hub"
        const val KEY_CATEGORIES = "categories"
        const val KEY_LIVE_CHANNELS = "live_channels"
        const val KEY_CATALOG_SUCCESS = "catalog_success"
        const val KEY_LIVE_SUCCESS = "live_success"
        const val KEY_CATALOG_DIAGNOSTIC = "catalog_diagnostic"
        const val KEY_TIME_SCHEMA = "availability_time_schema"
        const val CURRENT_TIME_SCHEMA = 2
    }
}
