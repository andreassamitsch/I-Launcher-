package com.andreassamitsch.joyntv

import android.content.Context

/**
 * Compatibility hook kept while the Mysterium transport is migrated from connect-proxy to
 * app-scoped WireGuard.
 *
 * The previous implementation listened to failures from the local HTTP/TLS proxy bridge and then
 * requested another short-lived proxy lease. That transport is no longer used. WireGuard rotation
 * has to be driven by VPN/tunnel or Joyn probe state instead; letting a stale proxy callback start
 * it would mix two incompatible transports and could also attempt to start a VPN in the background
 * without the Android VPN permission flow.
 *
 * Dedicated WireGuard failover will be added after the first on-device tunnel test. Until then this
 * object deliberately only removes the legacy bridge callback.
 */
internal object JoynMysteriumAutoFailover {
    fun install(context: Context) {
        // Touch applicationContext so callers may keep the same install contract during migration.
        context.applicationContext
        JoynProxySettings.setConnectFailureHandler(null)
    }
}
