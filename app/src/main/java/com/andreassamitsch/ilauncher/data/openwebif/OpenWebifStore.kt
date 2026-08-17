package com.andreassamitsch.ilauncher.data.openwebif

import android.content.Context
import com.google.gson.Gson

internal class OpenWebifStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val gson = Gson()

    fun loadConfig(): OpenWebifConfig? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return OpenWebifConfig(
            baseUrl = baseUrl,
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            password = preferences.getString(KEY_PASSWORD, "").orEmpty(),
            selectedBouquetRef = preferences.getString(KEY_SELECTED_BOUQUET, null),
        )
    }

    fun saveConnection(baseUrl: String, username: String, password: String) {
        val oldBaseUrl = preferences.getString(KEY_BASE_URL, null)
        preferences.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()

        if (oldBaseUrl != null && oldBaseUrl != baseUrl) {
            preferences.edit()
                .remove(KEY_SELECTED_BOUQUET)
                .remove(KEY_SNAPSHOT)
                .apply()
        }
    }

    fun saveSelectedBouquet(serviceReference: String?) {
        preferences.edit().apply {
            if (serviceReference.isNullOrBlank()) {
                remove(KEY_SELECTED_BOUQUET)
            } else {
                putString(KEY_SELECTED_BOUQUET, serviceReference)
            }
        }.apply()
    }

    fun saveSnapshot(snapshot: OpenWebifCachedSnapshot) {
        preferences.edit()
            .putString(KEY_SNAPSHOT, gson.toJson(snapshot))
            .apply()
    }

    fun loadSnapshot(config: OpenWebifConfig): OpenWebifCachedSnapshot? {
        val json = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        val snapshot = runCatching {
            gson.fromJson(json, OpenWebifCachedSnapshot::class.java)
        }.getOrNull() ?: return null
        return snapshot
            .takeIf { it.baseUrl == config.baseUrl }
            ?.let { cached ->
                cached.copy(channels = OpenWebifMapper.sanitizeChannels(cached.channels))
            }
    }

    companion object {
        private const val PREFERENCES_NAME = "openwebif"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SELECTED_BOUQUET = "selected_bouquet"
        private const val KEY_SNAPSHOT = "snapshot"
    }
}
