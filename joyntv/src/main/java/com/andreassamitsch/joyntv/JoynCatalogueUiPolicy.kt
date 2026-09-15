package com.andreassamitsch.joyntv

/**
 * Keeps internal Joyn navigation helpers out of the public catalogue UI.
 *
 * LandingPageClient exposes teaser/category targets that are useful to Joyn's web frontend but do
 * not resolve to standalone pages through the persisted browse queries used by this client. The
 * clearest examples are /serien/genre/* and /filme/genre/*: opening them yields zero blocks. They
 * therefore must not be presented as working categories in the standalone app.
 */
internal fun JoynCataloguePage.forJoynUi(): JoynCataloguePage = copy(
    lanes = lanes.mapNotNull { lane ->
        val visibleItems = lane.items.filterNot(JoynMediaItem::isUnsupportedJoynBrowseTarget)
        lane.copy(items = visibleItems).takeIf { visibleItems.isNotEmpty() }
    },
)

internal fun JoynMediaItem.isUnsupportedJoynBrowseTarget(): Boolean {
    if (type == JoynMediaType.CATEGORY) return true
    val normalizedPath = path?.trim().orEmpty()
    return normalizedPath.contains("/genre/", ignoreCase = true)
}

internal fun String.joynDisplayTitleOrNull(): String? =
    trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
