package com.andreassamitsch.joyntv

/**
 * Extracts the real programme artwork from a public Joyn detail page.
 *
 * Joyn serves several independent image assets for one programme. The lightweight GraphQL teaser
 * can contain only an art-logo while the public detail page also contains the 16:9 card/hero art.
 * Prefer the same profile Joyn's phone web UI uses for catalogue cards, then fall back to the
 * landscape hero profile. Logo profiles are deliberately never returned as content artwork.
 */
internal fun selectJoynPublicPageArtwork(html: String): String? {
    val normalized = html
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u003A", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\/", "/")
        .replace("&amp;", "&")

    return JOYN_IMAGE_URL.findAll(normalized)
        .map { it.value.trimJoynHtmlUrl() }
        .distinct()
        .map { url -> url to joynArtworkProfileScore(url) }
        .filter { (_, score) -> score >= 0 }
        .maxByOrNull { (_, score) -> score }
        ?.first
}

private fun joynArtworkProfileScore(url: String): Int {
    val lower = url.lowercase()
    if (
        "artlogo" in lower ||
        "brand-logo" in lower ||
        "brand_logo" in lower ||
        "livestream-logo" in lower ||
        "livestream_logo" in lower ||
        "/logo/" in lower
    ) {
        return -1
    }

    return when {
        "nextgen-webphone-primary-768x432" in lower -> 100
        "nextgen-webphone-primary" in lower -> 95
        "nextgen-web-herolandscape" in lower -> 90
        "herolandscape" in lower -> 85
        "nextgen-web-primary" in lower -> 80
        "primary-" in lower -> 70
        "/ingest/" in lower && (".jpg/" in lower || ".jpeg/" in lower || ".png/" in lower) -> 20
        else -> -1
    }
}

private fun String.trimJoynHtmlUrl(): String =
    trimEnd(')', ']', '}', ',', ';', '\\')

private val JOYN_IMAGE_URL = Regex(
    pattern = """https://img\.joyn\.de/[^\s\"'<>]+""",
    option = RegexOption.IGNORE_CASE,
)
