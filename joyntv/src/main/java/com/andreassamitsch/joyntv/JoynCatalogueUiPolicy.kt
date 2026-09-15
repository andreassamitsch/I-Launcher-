package com.andreassamitsch.joyntv

import java.text.Normalizer
import java.util.Locale

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
 *
 * Sender-/Mediatheken-Seiten are transformed from Joyn's one very long "Sendungen" shelf into a
 * TV-friendly structure: a short highlight shelf in Joyn's original order, followed by all shows
 * alphabetically grouped into compact letter ranges. This keeps remote navigation manageable while
 * preserving every item returned by Joyn.
 */
internal fun JoynCataloguePage.forJoynUi(): JoynCataloguePage {
    val visibleLanes = lanes.mapNotNull { lane ->
        if (lane.isRedundantJoynLiveLane()) return@mapNotNull null
        val visibleItems = lane.items.filterNot { it.isUnsupportedJoynBrowseTarget() }
        lane.copy(items = visibleItems).takeIf { visibleItems.isNotEmpty() }
    }

    val structuredLanes = when {
        visibleLanes.size == 1 && visibleLanes.first().id.startsWith("channel:") ->
            visibleLanes.first().toStructuredChannelLanes()
        else -> visibleLanes
    }

    // Mediatheken/channel shelves are useful, but they are poor hero candidates on a phone because
    // the first item is usually a station logo. Keep Joyn's actual content shelves first and move
    // pure channel shelves behind them. Card order inside every shelf stays unchanged.
    val (channelOnly, content) = structuredLanes.partition { lane ->
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

private fun JoynLane.toStructuredChannelLanes(): List<JoynLane> {
    val unique = items.distinctBy { it.id }
    if (unique.isEmpty()) return emptyList()

    val alphabetical = unique.sortedWith(
        compareBy<JoynMediaItem> { it.title.joynAlphabeticalKey() }
            .thenBy { it.title.lowercase(Locale.GERMAN) },
    )

    // Small libraries do not need duplicated highlight + alphabet shelves.
    if (unique.size < CHANNEL_HIGHLIGHT_THRESHOLD) {
        return listOf(copy(id = "$id:az", title = "Alle Sendungen A–Z", items = alphabetical))
    }

    val result = mutableListOf<JoynLane>()
    result += copy(
        id = "$id:highlights",
        title = "Highlights",
        items = unique.take(CHANNEL_HIGHLIGHT_COUNT),
    )

    CHANNEL_BUCKETS.forEach { bucket ->
        val bucketItems = alphabetical.filter { item -> bucket.accepts(item.title.joynAlphabeticalKey()) }
        if (bucketItems.isNotEmpty()) {
            result += copy(
                id = "$id:az:${bucket.id}",
                title = bucket.title,
                items = bucketItems,
            )
        }
    }

    return result
}

private fun String.joynAlphabeticalKey(): String {
    val normalized = Normalizer.normalize(trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .uppercase(Locale.GERMAN)
        .trimStart { !it.isLetterOrDigit() }
    return normalized
}

private data class JoynAlphabetBucket(
    val id: String,
    val title: String,
    val accepts: (String) -> Boolean,
)

private val CHANNEL_BUCKETS = listOf(
    JoynAlphabetBucket("a-f", "Sendungen A–F") { key -> key.firstOrNull() in 'A'..'F' },
    JoynAlphabetBucket("g-l", "Sendungen G–L") { key -> key.firstOrNull() in 'G'..'L' },
    JoynAlphabetBucket("m-r", "Sendungen M–R") { key -> key.firstOrNull() in 'M'..'R' },
    JoynAlphabetBucket("s-z", "Sendungen S–Z") { key -> key.firstOrNull() in 'S'..'Z' },
    JoynAlphabetBucket("other", "Weitere Sendungen") { key -> key.firstOrNull() !in 'A'..'Z' },
)

private const val CHANNEL_HIGHLIGHT_COUNT = 10
private const val CHANNEL_HIGHLIGHT_THRESHOLD = 8
