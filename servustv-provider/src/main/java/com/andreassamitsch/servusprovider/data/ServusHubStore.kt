package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.google.gson.reflect.TypeToken

class ServusHubStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val categoryListType = object : TypeToken<List<ServusCategory>>() {}.type
    private val liveListType = object : TypeToken<List<ServusLiveChannel>>() {}.type

    fun loadCategories(): List<ServusCategory> = loadList(KEY_CATEGORIES, categoryListType)

    fun loadLiveChannels(): List<ServusLiveChannel> = loadList(KEY_LIVE_CHANNELS, liveListType)

    fun saveCatalog(categories: List<ServusCategory>, refreshedAtMillis: Long) {
        preferences.edit()
            .putString(KEY_CATEGORIES, ServusNetwork.gson.toJson(categories))
            .putLong(KEY_CATALOG_SUCCESS, refreshedAtMillis)
            .apply()
    }

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
    }
}
