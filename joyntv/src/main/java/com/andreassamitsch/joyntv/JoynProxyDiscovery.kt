package com.andreassamitsch.joyntv

internal data class JoynProxyDiscoveryProgress(
    val message: String,
    val attempted: Int = 0,
    val total: Int = 0,
)

internal data class JoynProxyDiscoveryResult(
    val config: JoynProxyConfig?,
    val candidates: Int,
    val attempted: Int,
    val message: String,
    val expiresAt: String = "",
    /** True when a non-proxy transport (currently app-scoped Mysterium WireGuard) is active. */
    val activated: Boolean = false,
)
