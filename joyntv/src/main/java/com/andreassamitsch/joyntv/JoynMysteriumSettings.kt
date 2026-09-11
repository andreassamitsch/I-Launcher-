package com.andreassamitsch.joyntv

import android.content.Context
import java.time.Instant

internal data class JoynMysteriumCountrySettings(
    val country: JoynCountry,
    val maxAttempts: Int = DEFAULT_ATTEMPTS,
    val allTraffic: Boolean = false,
) {
    companion object {
        const val DEFAULT_ATTEMPTS = 25
    }
}

/** Persistent Mysterium residential-proxy settings, stored separately for every Joyn market. */
internal class JoynMysteriumSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun countrySettings(country: JoynCountry): JoynMysteriumCountrySettings {
        val attempts = prefs.getInt(key(country, "max_attempts"), JoynMysteriumCountrySettings.DEFAULT_ATTEMPTS)
            .coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)
        return JoynMysteriumCountrySettings(
            country = country,
            maxAttempts = attempts,
            allTraffic = prefs.getBoolean(key(country, "all_traffic"), false),
        )
    }

    fun saveCountrySettings(settings: JoynMysteriumCountrySettings) {
        prefs.edit()
            .putInt(key(settings.country, "max_attempts"), settings.maxAttempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS))
            .putBoolean(key(settings.country, "all_traffic"), settings.allTraffic)
            .apply()
    }

    fun saveLastSuccessful(
        country: JoynCountry,
        config: JoynProxyConfig,
        expiresAt: String = "",
    ) {
        prefs.edit()
            .putString(key(country, "host"), config.host)
            .putInt(key(country, "port"), config.port)
            .putString(key(country, "username"), config.username)
            .putString(key(country, "password"), config.password)
            .putString(key(country, "source"), config.source)
            .putString(key(country, "expires_at"), expiresAt)
            .putLong(key(country, "verified_at"), config.lastVerifiedAtEpochMs)
            .putBoolean(key(country, "all_traffic"), config.allTraffic)
            .apply()
    }

    fun lastSuccessful(country: JoynCountry): JoynProxyConfig? {
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
            allTraffic = prefs.getBoolean(key(country, "all_traffic"), false),
            source = prefs.getString(key(country, "source"), "Mysterium · Residential · ${country.name}").orEmpty(),
            latencyMs = -1L,
            lastVerifiedAtEpochMs = prefs.getLong(key(country, "verified_at"), 0L),
        )
    }

    fun lastExpiresAt(country: JoynCountry): String =
        prefs.getString(key(country, "expires_at"), "").orEmpty()

    fun leaseNeedsRefresh(country: JoynCountry, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val config = lastSuccessful(country) ?: return true
        val expires = lastExpiresAt(country)
        if (expires.isNotBlank()) {
            val expiryMs = runCatching { Instant.parse(expires).toEpochMilli() }.getOrNull()
            if (expiryMs != null) return expiryMs <= nowEpochMs + EXPIRY_MARGIN_MS
        }
        return config.lastVerifiedAtEpochMs <= 0L ||
            nowEpochMs - config.lastVerifiedAtEpochMs >= FALLBACK_REFRESH_MS
    }

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

    private fun key(country: JoynCountry, suffix: String) =
        "mysterium_${country.name.lowercase()}_$suffix"

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        const val MIN_ATTEMPTS = 1
        const val MAX_ATTEMPTS = 100
        private const val EXPIRY_MARGIN_MS = 90_000L
        private const val FALLBACK_REFRESH_MS = 20 * 60_000L
    }
}
