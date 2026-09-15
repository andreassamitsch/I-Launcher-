package com.andreassamitsch.joyntv

/**
 * Keeps internal Joyn navigation helpers out of the public catalogue UI.
 *
 * LandingPageClient exposes teaser/category targets that are useful to Joyn's web frontend but do
 * not resolve to standalone pages through the persisted browse queries used by this client. The
 * clearest examples are genre paths below /serien/genre/ and /filme/genre/: opening them yields
 * zero blocks. They therefore must not be presented as working categories in the standalone app.
 *
 * The Start page also contains a catalogue lane named "Live-TV zappn". Our app already renders the
 * real multi-country live data as "Jetzt live" directly above the catalogue, including EPG and
 * proper channel logos. Keeping Joyn's teaser lane only creates a second, mostly empty live row, so
 * it is deliberately suppressed.
 */
internal fun JoynCataloguePage.forJoynUi(): JoynCataloguePage {
    val visibleLanes = lanes.mapNotNull { lane ->
        if (lane.isRedundantJoynLiveLane()) return@mapNotNull null
        val visibleItems = lane.items.filterNot { it.isUnsupportedJoynBrowseTarget() }
        lane.copy(items = visibleItems).takeIf { visibleItems.isNotEmpty() }
    }

    // Mediatheken/channel shelves are useful, but they are poor hero candidates on a phone because
    // the first item is usually a station logo. Keep Joyn's actual content shelves first and move
    // pure channel shelves behind them. Card order inside every shelf stays unchanged.
    val (channelOnly, content) = visibleLanes.partition { lane ->
        lane.items.isNotEmpty() && lane.items.all { it.type == JoynMediaType.CHANNEL }
    }

    return copy(lanes = content + channelOnly)
}

internal fun JoynMediaItem.isUnsupportedJoynBrowseTarget(): Boolean {
    if (type == JoynMediaType.CATEGORY) return true
    val normalizedPath = path?.trim().orEmpty()
    return normalizedPath.contains("/genre/", ignoreCase = true)
}

internal fun String.joynDisplayTitleOrNull(): String? =
    trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

private fun JoynLane.isRedundantJoynLiveLane(): Boolean {
    val normalized = title.trim().lowercase().replace('–', '-').replace('—', '-')
    return normalized == "live-tv zappn" || normalized == "live tv zappn"
}
