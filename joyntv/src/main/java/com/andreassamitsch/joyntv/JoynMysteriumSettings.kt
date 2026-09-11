package com.andreassamitsch.joyntv

import android.content.Context

internal data class JoynMysteriumCountrySettings(
    val country: JoynCountry,
    val maxAttempts: Int = DEFAULT_ATTEMPTS,
) {
    companion object {
        const val DEFAULT_ATTEMPTS = 25
    }
}

/**
 * Persistent Mysterium settings. Each Joyn market keeps its own scan depth and last successful
 * residential proxy lease. Account/session tokens are stored separately by JoynMysteriumApiClient.
 */
internal class JoynMysteriumSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun apiBaseUrl(): String = prefs
        .getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL)
        .orEmpty()
        .trim()
        .ifBlank { DEFAULT_API_BASE_URL }
        .trimEnd('/')

    fun saveApiBaseUrl(value: String) {
        prefs.edit()
            .putString(KEY_API_BASE_URL, normalizeApiBaseUrl(value))
            .apply()
    }

    fun countrySettings(country: JoynCountry): JoynMysteriumCountrySettings {
        val attempts = prefs.getInt(keyAttempts(country), JoynMysteriumCountrySettings.DEFAULT_ATTEMPTS)
            .coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)
        return JoynMysteriumCountrySettings(country = country, maxAttempts = attempts)
    }

    fun saveCountrySettings(settings: JoynMysteriumCountrySettings) {
        prefs.edit()
            .putInt(keyAttempts(settings.country), settings.maxAttempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS))
            .apply()
    }

    fun saveLastSuccessful(country: JoynCountry, config: JoynProxyConfig, expiresAt: String = "") {
        prefs.edit()
            .putString(key(country, "host"), config.host)
            .putInt(key(country, "port"), config.port)
            .putString(key(country, "username"), config.username)
            .putString(key(country, "password"), config.password)
            .putString(key(country, "source"), config.source)
            .putString(key(country, "expires_at"), expiresAt)
            .putLong(key(country, "verified_at"), config.lastVerifiedAtEpochMs)
            .apply()
    }

    fun lastSuccessful(country: JoynCountry, allTraffic: Boolean): JoynProxyConfig? {
        val host = prefs.getString(key(country, "host"), "").orEmpty()
        val port = prefs.getInt(key(country, "port"), 0)
        if (host.isBlank() || port !in 1..65535) return null
        return JoynProxyConfig(
            enabled = true,
            automatic = true,
            transport = JoynProxyTransport.HTTP,
            host = host,
            port = port,
            username = prefs.getString(key(country, "username"), "").orEmpty(),
            password = prefs.getString(key(country, "password"), "").orEmpty(),
            allTraffic = allTraffic,
            source = prefs.getString(key(country, "source"), "Mysterium · Residential · ${country.name}").orEmpty(),
            latencyMs = -1L,
            lastVerifiedAtEpochMs = prefs.getLong(key(country, "verified_at"), 0L),
        )
    }

    fun lastExpiresAt(country: JoynCountry): String =
        prefs.getString(key(country, "expires_at"), "").orEmpty()

    fun clearLastSuccessful(country: JoynCountry) {
        prefs.edit()
            .remove(key(country, "host"))
            .remove(key(country, "port"))
            .remove(key(country, "username"))
            .remove(key(country, "password"))
            .remove(key(country, "source"))
            .remove(key(country, "expires_at"))
            .remove(key(country, "verified_at"))
            .apply()
    }

    private fun keyAttempts(country: JoynCountry) = key(country, "max_attempts")

    private fun key(country: JoynCountry, suffix: String) =
        "mysterium_${country.name.lowercase()}_$suffix"

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_API_BASE_URL = "mysterium_api_base_url"

        // Current Mysterium consumer app is built against the hosted VPN API. Keep the URL
        // editable in the beta UI so a backend migration never requires a Joyn TV rebuild.
        const val DEFAULT_API_BASE_URL = "https://api.mysteriumvpn.com/api/v1"
        const val LOCAL_NODE_API_BASE_URL = "http://127.0.0.1:3030/api/v1"
        const val MIN_ATTEMPTS = 1
        const val MAX_ATTEMPTS = 100

        fun normalizeApiBaseUrl(value: String): String {
            var normalized = value.trim().trimEnd('/')
            if (normalized.isBlank()) normalized = DEFAULT_API_BASE_URL
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }
    }
}
