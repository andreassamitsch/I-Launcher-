package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.google.gson.reflect.TypeToken

class ServusNewsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val episodeListType = object : TypeToken<List<ServusNewsEpisode>>() {}.type

    fun loadEpisodes(): List<ServusNewsEpisode> {
        val raw = preferences.getString(KEY_EPISODES, null) ?: return emptyList()
        val episodes = runCatching {
            ServusNetwork.gson.fromJson<List<ServusNewsEpisode>>(raw, episodeListType).orEmpty()
        }.getOrDefault(emptyList())
        if (episodes.isEmpty()) return episodes

        val timeMigrated = if (preferences.getInt(KEY_TIME_SCHEMA, 1) >= CURRENT_TIME_SCHEMA) {
            episodes
        } else {
            episodes.map { it.copy(publishedAtMillis = null) }
        }
        val canonical = timeMigrated.map(ServusBranding::canonicalizeEpisode)

        if (canonical != episodes || preferences.getInt(KEY_TIME_SCHEMA, 1) < CURRENT_TIME_SCHEMA) {
            preferences.edit()
                .putString(KEY_EPISODES, ServusNetwork.gson.toJson(canonical))
                .putInt(KEY_TIME_SCHEMA, CURRENT_TIME_SCHEMA)
                .apply()
        }
        return canonical
    }

    fun save(result: ServusRefreshResult) {
        val canonicalEpisodes = result.episodes.map(ServusBranding::canonicalizeEpisode)
        preferences.edit()
            .putString(KEY_EPISODES, ServusNetwork.gson.toJson(canonicalEpisodes))
            .putLong(KEY_LAST_SUCCESS, result.refreshedAtMillis)
            .putInt(KEY_TIME_SCHEMA, CURRENT_TIME_SCHEMA)
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
        const val KEY_TIME_SCHEMA = "availability_time_schema"
        const val CURRENT_TIME_SCHEMA = 2
    }
}
