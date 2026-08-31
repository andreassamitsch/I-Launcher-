package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.google.gson.reflect.TypeToken

class ServusNewsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val episodeListType = object : TypeToken<List<ServusNewsEpisode>>() {}.type

    fun loadEpisodes(): List<ServusNewsEpisode> {
        val raw = preferences.getString(KEY_EPISODES, null) ?: return emptyList()
        return runCatching {
            ServusNetwork.gson.fromJson<List<ServusNewsEpisode>>(raw, episodeListType).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun save(result: ServusRefreshResult) {
        preferences.edit()
            .putString(KEY_EPISODES, ServusNetwork.gson.toJson(result.episodes))
            .putLong(KEY_LAST_SUCCESS, result.refreshedAtMillis)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun saveError(message: String) {
        preferences.edit().putString(KEY_LAST_ERROR, message.take(300)).apply()
    }

    fun lastSuccessMillis(): Long = preferences.getLong(KEY_LAST_SUCCESS, 0L)
    fun lastError(): String? = preferences.getString(KEY_LAST_ERROR, null)

    private companion object {
        const val PREFS_NAME = "servus_news"
        const val KEY_EPISODES = "episodes"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
    }
}
