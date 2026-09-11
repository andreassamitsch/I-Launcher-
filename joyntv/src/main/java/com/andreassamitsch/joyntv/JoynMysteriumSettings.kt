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

/** A Joyn-approved Residential exit that should be restored whenever this market is active. */
internal data class JoynMysteriumWireGuardProfile(
    val country: JoynCountry,
    val exitIp: String,
    val configTemplate: String,
    val providerHash: String = "",
    val connectionId: String = "",
    val enabled: Boolean = true,
    val verifiedAtEpochMs: Long = 0L,
)

/** Persistent Mysterium settings, stored separately for every Joyn market. */
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

    /** Candidate bookkeeping used while the scanner is still testing exits. */
    fun saveWireGuardCandidate(country: JoynCountry, lease: JoynMysteriumWireGuardLease) {
        prefs.edit()
            .putString(key(country, "wg_candidate_exit_ip"), lease.exitIp.trim())
            .putString(key(country, "wg_candidate_config"), lease.config)
            .putString(key(country, "wg_candidate_hash"), lease.providerHash)
            .putString(key(country, "wg_candidate_id"), lease.id)
            .apply()
    }

    /**
     * Persists the exact exit that was actually measured through the tunnel and accepted by Joyn.
     * Mysterium marks exit_ip as optional, so the verified trace IP is authoritative here.
     */
    fun saveApprovedWireGuardProfile(
        country: JoynCountry,
        lease: JoynMysteriumWireGuardLease,
        verifiedExitIp: String,
    ): JoynMysteriumWireGuardProfile {
        val exitIp = verifiedExitIp.trim()
        require(exitIp.isNotBlank()) { "Verifizierte Mysterium Exit-IP fehlt." }
        require(lease.config.isNotBlank()) { "Verifizierte WireGuard-Konfiguration fehlt." }
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(key(country, "wg_exit_ip"), exitIp)
            .putString(key(country, "wg_config"), lease.config)
            .putString(key(country, "wg_hash"), lease.providerHash)
            .putString(key(country, "wg_id"), lease.id)
            .putBoolean(key(country, "wg_enabled"), true)
            .putLong(key(country, "wg_verified_at"), now)
            .apply()
        return JoynMysteriumWireGuardProfile(
            country = country,
            exitIp = exitIp,
            configTemplate = lease.config,
            providerHash = lease.providerHash,
            connectionId = lease.id,
            enabled = true,
            verifiedAtEpochMs = now,
        )
    }

    /** Legacy/fallback promotion for candidates produced by builds that did expose exit_ip. */
    fun promoteWireGuardCandidate(country: JoynCountry): JoynMysteriumWireGuardProfile? {
        val exitIp = prefs.getString(key(country, "wg_candidate_exit_ip"), "").orEmpty().trim()
        val config = prefs.getString(key(country, "wg_candidate_config"), "").orEmpty()
        if (exitIp.isBlank() || config.isBlank()) return null

        val now = System.currentTimeMillis()
        val providerHash = prefs.getString(key(country, "wg_candidate_hash"), "").orEmpty()
        val connectionId = prefs.getString(key(country, "wg_candidate_id"), "").orEmpty()
        prefs.edit()
            .putString(key(country, "wg_exit_ip"), exitIp)
            .putString(key(country, "wg_config"), config)
            .putString(key(country, "wg_hash"), providerHash)
            .putString(key(country, "wg_id"), connectionId)
            .putBoolean(key(country, "wg_enabled"), true)
            .putLong(key(country, "wg_verified_at"), now)
            .apply()
        return JoynMysteriumWireGuardProfile(
            country = country,
            exitIp = exitIp,
            configTemplate = config,
            providerHash = providerHash,
            connectionId = connectionId,
            enabled = true,
            verifiedAtEpochMs = now,
        )
    }

    /** Updates a persistent profile after target_ip restored the same accepted exit. */
    fun saveWireGuardProfile(country: JoynCountry, lease: JoynMysteriumWireGuardLease) {
        val current = wireGuardProfile(country)
        val exitIp = lease.exitIp.trim().ifBlank { current?.exitIp.orEmpty() }
        if (exitIp.isBlank() || lease.config.isBlank()) return
        prefs.edit()
            .putString(key(country, "wg_exit_ip"), exitIp)
            .putString(key(country, "wg_config"), lease.config)
            .putString(key(country, "wg_hash"), lease.providerHash)
            .putString(key(country, "wg_id"), lease.id)
            .putBoolean(key(country, "wg_enabled"), true)
            .putLong(key(country, "wg_verified_at"), System.currentTimeMillis())
            .apply()
    }

    fun wireGuardProfile(country: JoynCountry): JoynMysteriumWireGuardProfile? {
        val exitIp = prefs.getString(key(country, "wg_exit_ip"), "").orEmpty().trim()
        val config = prefs.getString(key(country, "wg_config"), "").orEmpty()
        if (exitIp.isBlank() || config.isBlank()) return null
        return JoynMysteriumWireGuardProfile(
            country = country,
            exitIp = exitIp,
            configTemplate = config,
            providerHash = prefs.getString(key(country, "wg_hash"), "").orEmpty(),
            connectionId = prefs.getString(key(country, "wg_id"), "").orEmpty(),
            enabled = prefs.getBoolean(key(country, "wg_enabled"), true),
            verifiedAtEpochMs = prefs.getLong(key(country, "wg_verified_at"), 0L),
        )
    }

    fun setWireGuardEnabled(country: JoynCountry, enabled: Boolean) {
        prefs.edit().putBoolean(key(country, "wg_enabled"), enabled).apply()
    }

    fun clearWireGuardProfile(country: JoynCountry) {
        prefs.edit()
            .remove(key(country, "wg_exit_ip"))
            .remove(key(country, "wg_config"))
            .remove(key(country, "wg_hash"))
            .remove(key(country, "wg_id"))
            .remove(key(country, "wg_enabled"))
            .remove(key(country, "wg_verified_at"))
            .remove(key(country, "wg_candidate_exit_ip"))
            .remove(key(country, "wg_candidate_config"))
            .remove(key(country, "wg_candidate_hash"))
            .remove(key(country, "wg_candidate_id"))
            .apply()
    }

    // Legacy connect-proxy profile storage retained for migration/compatibility with old builds.
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
