package com.andreassamitsch.joyntv

/**
 * Selects artwork by semantic card type instead of treating logos, posters and landscape art as
 * interchangeable images.
 *
 * Joyn sometimes returns an art-logo through a generic image slot. Rendering that as a full-card
 * background produces the duplicated/mostly empty cards seen on phone layouts. Channel/library
 * cards therefore only use a distinct landscape backdrop; otherwise the real logo becomes the
 * centered fallback. VOD cards prefer their content image and fall back to landscape art.
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
        // A channel's generic primary image is very often just another logo treatment. Only use a
        // dedicated/different landscape image as background; otherwise a centered logo is cleaner.
        val landscape = usableContentArt(backdrop)?.takeUnless { joynSameImageAsset(it, image) }
        return JoynTileArtwork(
            primary = landscape,
            fallback = null,
            centerLogo = landscape == null && logo != null,
            overlayLogo = landscape != null && logo != null,
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
    val normalized = substringBefore('?').substringBefore('#').lowercase()
    return normalized.contains("artlogo") ||
        normalized.contains("brand-logo") ||
        normalized.contains("brand_logo") ||
        normalized.contains("livestream-logo") ||
        normalized.contains("livestream_logo") ||
        normalized.contains("/logo/") ||
        normalized.substringAfterLast('/').startsWith("logo-") ||
        normalized.substringAfterLast('/').startsWith("logo_")
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
