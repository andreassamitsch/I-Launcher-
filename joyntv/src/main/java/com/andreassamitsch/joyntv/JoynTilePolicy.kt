package com.andreassamitsch.joyntv

/**
 * Selects artwork by semantic card type instead of treating logos, posters and landscape art as
 * interchangeable images.
 *
 * Joyn sometimes returns an art-logo through a generic image slot. Rendering that as a full-card
 * background produces the duplicated/mostly empty cards seen on phone layouts. Real brand/content
 * artwork should still be used even when Joyn exposes the same URL in both primary and backdrop
 * fields; logos only become the centered fallback when no usable content art exists.
 */
internal data class JoynTileArtwork(
    val primary: String?,
    val fallback: String?,
    val centerLogo: Boolean,
    val overlayLogo: Boolean,
)

internal fun JoynMediaItem.resolveJoynTileArtwork(): JoynTileArtwork {
    val logo = logoUrl.cleanJoynImageUrl()
    val image = imageUrl.cleanJoynImageUrl()
    val backdrop = backdropUrl.cleanJoynImageUrl()

    fun usableContentArt(url: String?): String? = url?.takeUnless { candidate ->
        candidate.looksLikeJoynLogoAsset() || joynSameImageAsset(candidate, logo)
    }

    if (type == JoynMediaType.CHANNEL) {
        // Brand/channel objects frequently expose one proper key-art URL in both image fields. That
        // is still valid artwork and must not be discarded merely because both URLs are identical.
        // Broadcaster logo variants are filtered by URL/profile tokens and by comparison with logoUrl.
        val primary = usableContentArt(backdrop) ?: usableContentArt(image)
        val fallback = when {
            primary == null -> null
            joynSameImageAsset(primary, backdrop) -> usableContentArt(image)
                ?.takeUnless { joynSameImageAsset(it, primary) }
            else -> usableContentArt(backdrop)?.takeUnless { joynSameImageAsset(it, primary) }
        }
        return JoynTileArtwork(
            primary = primary,
            fallback = fallback,
            centerLogo = primary == null && logo != null,
            overlayLogo = primary != null && logo != null,
        )
    }

    val primary = usableContentArt(image) ?: usableContentArt(backdrop)
    val fallback = when {
        primary == null -> null
        joynSameImageAsset(primary, image) -> usableContentArt(backdrop)
            ?.takeUnless { joynSameImageAsset(it, primary) }
        else -> usableContentArt(image)?.takeUnless { joynSameImageAsset(it, primary) }
    }

    return JoynTileArtwork(
        primary = primary,
        fallback = fallback,
        centerLogo = primary == null && logo != null,
        // Content logos are not overlaid on real posters/stills; the title already identifies them.
        overlayLogo = false,
    )
}

internal fun String.longestJoynTitleWordLength(): Int =
    split(Regex("\\s+"))
        .asSequence()
        .map { word -> word.trim { !it.isLetterOrDigit() }.length }
        .maxOrNull()
        ?: 0

private fun String?.cleanJoynImageUrl(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

private fun String.looksLikeJoynLogoAsset(): Boolean {
    // Do not strip the query before checking: Joyn CDN URLs often carry the decisive profile name
    // (for example nextgen-web-artlogo-...) in query/transform parameters rather than the path.
    val full = lowercase()
    val path = substringBefore('?').substringBefore('#').lowercase()
    val fileName = path.substringAfterLast('/')
    return full.contains("artlogo") ||
        full.contains("brand-logo") ||
        full.contains("brand_logo") ||
        full.contains("livestream-logo") ||
        full.contains("livestream_logo") ||
        full.contains("profile=logo") ||
        path.contains("/logo/") ||
        fileName.startsWith("logo-") ||
        fileName.startsWith("logo_")
}

private fun joynSameImageAsset(first: String?, second: String?): Boolean {
    if (first.isNullOrBlank() || second.isNullOrBlank()) return false
    return normalizeJoynImageAsset(first) == normalizeJoynImageAsset(second)
}

private fun normalizeJoynImageAsset(url: String): String =
    url.trim()
        .substringBefore('?')
        .substringBefore('#')
        .removeSuffix("/")
        .lowercase()
