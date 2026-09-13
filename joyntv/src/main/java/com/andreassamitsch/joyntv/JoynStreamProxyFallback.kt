package com.andreassamitsch.joyntv

import android.content.Context

/**
 * Learns which live channels need the media path itself routed through Mysterium.
 *
 * The user's configured proxy mode stays untouched. In CONTROL_ONLY mode we temporarily promote
 * the process route to all-traffic only while a known/problematic channel is playing. If the CDN
 * still rejects the same residential HTTP proxy with HTTP 403, the player can make one final
 * app-scoped WireGuard attempt. This is especially useful for live CDNs that require one stable
 * residential source IP across manifest, DRM and segment connections.
 */
internal class JoynStreamProxyFallback(context: Context) {
    private val appContext = context.applicationContext
    private val proxySettings = JoynProxySettings(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mysteriumSettings = JoynMysteriumSettings(appContext)
    private val mysteriumApiClient = JoynMysteriumApiClient(appContext)
    private val mysteriumWireGuardApi = JoynMysteriumWireGuardApiClient(appContext, mysteriumApiClient)

    /**
     * Restores the user's saved route and, for a previously learned channel, enables temporary
     * full-proxy before Media3 opens the DASH manifest.
     *
     * @return true when the media request should currently be considered full-proxy routed.
     */
    fun prepareLiveChannel(channelId: String): Boolean {
        proxySettings.restoreSavedRouting()
        val base = proxySettings.current()
        if (!base.isUsable) return false
        if (base.allTraffic) return true
        if (!base.isMysterium || !needsFullProxy(channelId)) return false
        return proxySettings.enableTemporaryAllTraffic()
    }

    /** Tries full-proxy with the currently active Mysterium lease without changing saved settings. */
    fun tryTemporaryFullProxy(): Boolean {
        val base = proxySettings.current()
        if (!base.isUsable || !base.isMysterium || base.allTraffic) return false
        return proxySettings.enableTemporaryAllTraffic()
    }

    /**
     * Last-resort media fallback for a channel whose manifest/CDN still returns HTTP 403 through the
     * HTTP CONNECT route. The proxy pin is released, proxy routing is switched to explicit direct
     * mode and only this APK is moved onto Mysterium WireGuard. The already resolved Joyn playback
     * URL can then be retried by Media3 without another entitlement request.
     */
    suspend fun tryWireGuardFallback(channelId: String): Result<Unit> = runCatching {
        val country = liveCountry(channelId)
            ?: error("Live-Land konnte aus der Sender-ID nicht ermittelt werden.")
        val fallbackProxy = mysteriumSettings.lastSuccessful(country)

        // A process proxy pin would override the tunnel's direct network path. Release it before
        // installing the app-scoped WireGuard interface.
        JoynPlaybackRouteGuard.release(appContext)
        JoynProxySettings.installDirectForTunnel()

        try {
            val activeCountry = JoynMysteriumWireGuard.activeCountry()
            if (activeCountry != null && activeCountry != country && JoynMysteriumWireGuard.isConnected(appContext)) {
                JoynMysteriumWireGuard.disconnect(appContext).getOrThrow()
            }

            val publicKey = JoynMysteriumWireGuard.publicKey(appContext, country)
            val profile = mysteriumSettings.wireGuardProfile(country)?.takeIf { it.enabled }

            if (profile != null) {
                suspend fun activateTarget(forceRefresh: Boolean, timeoutMs: Long) {
                    val lease = mysteriumWireGuardApi.requestResidentialTarget(
                        country = country,
                        publicKey = publicKey,
                        targetIp = profile.exitIp,
                        forceRefresh = forceRefresh,
                    ).getOrThrow()
                    JoynMysteriumWireGuard.connect(
                        context = appContext,
                        country = country,
                        configTemplate = lease.config,
                    ).getOrThrow()
                    JoynMysteriumTunnelProbe.awaitReady(
                        context = appContext,
                        country = country,
                        expectedExitIp = profile.exitIp,
                        timeoutMs = timeoutMs,
                    ).getOrThrow()
                    mysteriumSettings.saveWireGuardProfile(country, lease)
                }

                runCatching {
                    activateTarget(forceRefresh = false, timeoutMs = FAST_TUNNEL_READY_TIMEOUT_MS)
                }.getOrElse {
                    if (JoynMysteriumWireGuard.activeCountry() == country) {
                        runCatching { JoynMysteriumWireGuard.disconnect(appContext) }
                    }
                    activateTarget(forceRefresh = true, timeoutMs = REFRESH_TUNNEL_READY_TIMEOUT_MS)
                }
            } else {
                // Older/new installations may not have a previously approved WireGuard target yet.
                // Use one temporary Residential connection as a media-only fallback. It is not
                // promoted to the persistent approved profile until a dedicated scanner verifies it.
                val lease = mysteriumWireGuardApi.requestResidential(
                    country = country,
                    publicKey = publicKey,
                    resetConnection = true,
                ).getOrThrow()
                JoynMysteriumWireGuard.connect(
                    context = appContext,
                    country = country,
                    configTemplate = lease.config,
                ).getOrThrow()
                if (lease.exitIp.isNotBlank()) {
                    JoynMysteriumTunnelProbe.awaitReady(
                        context = appContext,
                        country = country,
                        expectedExitIp = lease.exitIp,
                        timeoutMs = REFRESH_TUNNEL_READY_TIMEOUT_MS,
                    ).getOrThrow()
                }
            }
        } catch (error: Throwable) {
            if (JoynMysteriumWireGuard.activeCountry() == country) {
                runCatching { JoynMysteriumWireGuard.disconnect(appContext) }
            }
            fallbackProxy?.copy(enabled = true, automatic = true)?.let(proxySettings::save)
            throw error
        }
    }

    /** Remember only after Media3 actually reaches READY through the fallback route. */
    fun confirmFullProxyRequired(channelId: String) {
        if (channelId.isBlank()) return
        val learned = prefs.getStringSet(KEY_CHANNELS, emptySet()).orEmpty().toMutableSet()
        if (learned.add(channelId)) {
            prefs.edit().putStringSet(KEY_CHANNELS, learned).apply()
        }
    }

    fun restoreSavedRouting() {
        proxySettings.restoreSavedRouting()
    }

    private fun needsFullProxy(channelId: String): Boolean =
        prefs.getStringSet(KEY_CHANNELS, emptySet()).orEmpty().contains(channelId)

    private fun liveCountry(channelId: String): JoynCountry? {
        if (!channelId.startsWith(MULTI_LIVE_PREFIX)) return null
        val payload = channelId.removePrefix(MULTI_LIVE_PREFIX)
        val countryName = payload.substringBefore(':')
        return runCatching { JoynCountry.valueOf(countryName) }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "joyn_stream_proxy_fallback"
        private const val KEY_CHANNELS = "full_proxy_live_channels"
        private const val MULTI_LIVE_PREFIX = "multi:"
        private const val FAST_TUNNEL_READY_TIMEOUT_MS = 3_500L
        private const val REFRESH_TUNNEL_READY_TIMEOUT_MS = 5_500L
    }
}
