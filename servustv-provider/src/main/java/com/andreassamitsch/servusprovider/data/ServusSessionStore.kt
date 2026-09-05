package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusApi

class ServusSessionStore(
    context: Context,
    private val api: ServusApi,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun get(forceRefresh: Boolean = false): ServusSession {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val token = preferences.getString(KEY_TOKEN, null)
            val country = preferences.getString(KEY_COUNTRY, null)
            val createdAt = preferences.getLong(KEY_CREATED_AT, 0L)
            if (!token.isNullOrBlank() && !country.isNullOrBlank() && now - createdAt < TOKEN_TTL_MILLIS) {
                return ServusSession(token, country, createdAt)
            }
        }

        val response = api.session()
        val token = requireNotNull(response.token?.takeIf { it.isNotBlank() }) {
            "ServusTV session did not contain a token"
        }
        val country = requireNotNull(response.countryCode?.lowercase()?.takeIf { it.isNotBlank() }) {
            "ServusTV session did not contain a country_code"
        }
        preferences.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_COUNTRY, country)
            .putLong(KEY_CREATED_AT, now)
            .apply()
        return ServusSession(token, country, now)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "servus_session"
        const val KEY_TOKEN = "token"
        const val KEY_COUNTRY = "country"
        const val KEY_CREATED_AT = "created_at"
        const val TOKEN_TTL_MILLIS = 3L * 60L * 60L * 1000L
    }
}
