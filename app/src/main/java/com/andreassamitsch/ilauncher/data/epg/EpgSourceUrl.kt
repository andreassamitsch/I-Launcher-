package com.andreassamitsch.ilauncher.data.epg

import java.net.URI

internal object EpgSourceUrl {
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!uri.userInfo.isNullOrBlank()) return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.fragment != null) return null
        return uri.toASCIIString()
    }

    fun label(url: String): String = runCatching {
        val uri = URI(url)
        buildString {
            append(uri.host ?: "EPG-Quelle")
            if (uri.port > 0 && uri.port != 80 && uri.port != 443) {
                append(':')
                append(uri.port)
            }
        }
    }.getOrDefault("EPG-Quelle")
}
