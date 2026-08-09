package com.andreassamitsch.ilauncher.data.openwebif

import java.net.URI

object OpenWebifUrl {
    fun normalize(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) return null

        val withScheme = if (SCHEME_REGEX.containsMatchIn(trimmed)) {
            trimmed
        } else {
            "http://$trimmed"
        }

        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return null
        }

        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        val normalizedPath = if (path.endsWith('/')) path else "$path/"
        return URI(
            scheme,
            null,
            uri.host,
            uri.port,
            normalizedPath,
            null,
            null,
        ).toASCIIString()
    }

    fun receiverLabel(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return if (uri.port >= 0) "$host:${uri.port}" else host
    }

    fun resolve(baseUrl: String, pathOrUrl: String?): String? {
        val value = pathOrUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val absolute = runCatching { URI(value) }.getOrNull()
        if (absolute?.isAbsolute == true) return absolute.toASCIIString()
        return runCatching { URI(baseUrl).resolve(value).toASCIIString() }.getOrNull()
    }

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
