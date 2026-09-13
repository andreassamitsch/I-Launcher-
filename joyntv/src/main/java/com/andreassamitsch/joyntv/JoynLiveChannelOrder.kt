package com.andreassamitsch.joyntv

/**
 * Curated combined AT/DE/CH Live-TV list plus logical sorting for the full per-country rows.
 *
 * The combined row deliberately does not expose every thematic/event/FAST stream Joyn happens to
 * return. It keeps the well-known general-interest channels in a stable order. When Joyn exposes
 * the same station more than once, the highest-resolution feed wins; the station's preferred origin
 * is only used as a tie-breaker at equal quality.
 *
 * The country rows, on the other hand, keep every channel the corresponding account may access.
 * Known stations are shown first in a familiar order, followed by the remaining linear channels
 * alphabetically and event streams after them.
 */
internal object JoynLiveChannelOrder {
    private const val NOT_LISTED = 10_000

    private val countryOrder = listOf(JoynCountry.AT, JoynCountry.DE, JoynCountry.CH)

    private val popularChannelsByCountry = mapOf(
        JoynCountry.AT to listOf(
            setOf("orf 1", "orf1"),
            // Only the Styrian ORF 2 regional feed is wanted in the combined TV list.
            setOf("orf 2 steiermark", "orf2 steiermark", "orf 2 st", "orf2 st"),
            setOf("servustv", "servus tv"),
            setOf("atv"),
            setOf("puls 4", "puls4"),
            setOf("orf iii", "orf 3", "orf3"),
            setOf("puls 24", "puls24"),
            setOf("atv2", "atv 2"),
            setOf("orf sport+", "orf sport +", "orf sport plus"),
        ),
        JoynCountry.DE to listOf(
            setOf("das erste", "ard", "ard das erste"),
            setOf("zdf"),
            setOf("sat 1", "sat1"),
            setOf("prosieben", "pro sieben"),
            setOf("rtl"),
            setOf("vox"),
            setOf("kabel eins", "kabeleins"),
            setOf("rtlzwei", "rtl zwei", "rtl2"),
            setOf("super rtl", "superrtl"),
            setOf("nitro", "rtl nitro"),
            setOf("rtl up", "rtlup"),
            setOf("voxup", "vox up"),
            setOf("3sat"),
            setOf("arte"),
            setOf("zdfneo", "zdf neo"),
            setOf("ntv", "n tv"),
            setOf("welt"),
            setOf("prosieben maxx", "pro sieben maxx"),
            setOf("sixx"),
            setOf("dmax"),
            setOf("tlc"),
        ),
        JoynCountry.CH to listOf(
            setOf("srf 1", "srf1"),
            setOf("srf zwei", "srf 2", "srf2"),
            setOf("3+", "3 plus"),
            setOf("4+", "4 plus"),
            setOf("5+", "5 plus"),
            setOf("6+", "6 plus"),
            // Joyn CH carries the RTL family while Joyn DE may not expose those live channels.
            setOf("rtl"),
            setOf("vox"),
            setOf("rtlzwei", "rtl zwei", "rtl2"),
            setOf("super rtl", "superrtl"),
            setOf("nitro", "rtl nitro"),
            setOf("rtl up", "rtlup"),
            setOf("voxup", "vox up"),
            setOf("ntv", "n tv"),
            setOf("tv24", "tv 24"),
            setOf("tv25", "tv 25"),
            setOf("srf info"),
        ),
    )

    fun sort(
        channels: List<Pair<JoynCountry, JoynLiveChannel>>,
    ): List<Pair<JoynCountry, JoynLiveChannel>> {
        val wanted = channels.filter { (country, channel) -> rankedChannel(country, channel.title) < NOT_LISTED }
        val deduplicated = deduplicate(wanted)

        return countryOrder.flatMap { country ->
            deduplicated
                .withIndex()
                .filter { it.value.first == country }
                .sortedWith(
                    compareBy<IndexedValue<Pair<JoynCountry, JoynLiveChannel>>>(
                        { rankedChannel(country, it.value.second.title) },
                        { it.index },
                    ),
                )
                .map { it.value }
        }
    }

    /**
     * Keep every accessible channel for one market, while making the row useful on TV: familiar
     * linear channels first, then the remaining linear channels alphabetically, and event streams
     * afterwards. Duplicate SD/HD representations of the same station collapse to the best feed.
     */
    fun sortCountry(country: JoynCountry, channels: List<JoynLiveChannel>): List<JoynLiveChannel> {
        val deduplicated = deduplicate(channels.map { country to it }).map { it.second }
        return deduplicated
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<JoynLiveChannel>>(
                    { channelTypeRank(it.value) },
                    { rankedChannel(country, it.value.title) },
                    { rankingKey(it.value.title) },
                    { it.index },
                ),
            )
            .map { it.value }
    }

    internal fun rankedChannel(country: JoynCountry, title: String): Int {
        val normalized = rankingKey(title)
        val priorities = popularChannelsByCountry[country].orEmpty()
        val rank = priorities.indexOfFirst { aliases -> normalized in aliases }
        return if (rank >= 0) rank else NOT_LISTED
    }

    private fun channelTypeRank(channel: JoynLiveChannel): Int = when (channel.type.uppercase()) {
        "LINEAR" -> 0
        "EVENT" -> 1
        else -> 2
    }

    private fun deduplicate(
        channels: List<Pair<JoynCountry, JoynLiveChannel>>,
    ): List<Pair<JoynCountry, JoynLiveChannel>> {
        val grouped = channels.groupBy { (_, channel) -> duplicateKey(channel.title) }
        val winners = grouped.values.map { duplicates ->
            if (duplicates.size == 1) return@map duplicates.first()

            val highestResolution = duplicates.maxOf { (_, channel) -> resolutionRank(channel) }
            val highestQualityFeeds = duplicates.filter { (_, channel) ->
                resolutionRank(channel) == highestResolution
            }

            val key = duplicateKey(duplicates.first().second.title)
            val preferredCountry = preferredOrigin(key)
            highestQualityFeeds.firstOrNull { it.first == preferredCountry }
                ?: countryOrder.firstNotNullOfOrNull { country ->
                    highestQualityFeeds.firstOrNull { it.first == country }
                }
                ?: highestQualityFeeds.first()
        }.toSet()
        return channels.filter { it in winners }
    }

    /**
     * Prefer Joyn's explicit `quality` field. Some feeds only carry the quality in their display
     * title, so title parsing is used as a fallback. The returned number roughly represents vertical
     * resolution and is only used to compare duplicate stations.
     */
    private fun resolutionRank(channel: JoynLiveChannel): Int {
        val explicit = qualityRank(channel.quality)
        return if (explicit > 0) explicit else qualityRank(channel.title)
    }

    private fun qualityRank(value: String?): Int {
        val normalized = value?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank()) return 0

        RESOLUTION_HEIGHT.findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?.let { return it }

        return when {
            "uhd" in normalized || FOUR_K.containsMatchIn(normalized) -> 2160
            "full hd" in normalized || "fullhd" in normalized || FHD.containsMatchIn(normalized) -> 1080
            HD.containsMatchIn(normalized) -> 720
            SD.containsMatchIn(normalized) -> 576
            else -> 0
        }
    }

    private fun rankingKey(title: String): String {
        var value = normalize(title)
        REGION_SUFFIXES.forEach { suffix ->
            if (value.endsWith(" $suffix")) value = value.removeSuffix(" $suffix").trim()
        }
        STREAM_SUFFIXES.forEach { suffix ->
            if (value.endsWith(" $suffix")) value = value.removeSuffix(" $suffix").trim()
        }
        return value
    }

    private fun duplicateKey(title: String): String = rankingKey(title)

    private fun preferredOrigin(channelKey: String): JoynCountry = when {
        channelKey in AUSTRIAN_ORIGIN_ALIASES -> JoynCountry.AT
        channelKey in SWISS_ORIGIN_ALIASES -> JoynCountry.CH
        else -> JoynCountry.DE
    }

    private fun normalize(value: String): String =
        value
            .lowercase()
            .replace(QUALITY_TOKEN, " ")
            .replace(Regex("[^a-z0-9äöü+]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private val REGION_SUFFIXES = setOf(
        "österreich", "austria", "at",
        "deutschland", "germany", "de",
        "schweiz", "switzerland", "ch",
    )

    private val STREAM_SUFFIXES = setOf("live", "livestream")

    private val QUALITY_TOKEN = Regex(
        "\\b(?:uhd|fhd|full\\s*hd|hd|sd|4k|(?:2160|1440|1080|720|576|540|480)[pi]?)\\b",
    )
    private val RESOLUTION_HEIGHT = Regex("(?:\\d{3,4}\\s*[x×]\\s*)?(2160|1440|1080|720|576|540|480)[pi]?")
    private val FOUR_K = Regex("\\b4k\\b")
    private val FHD = Regex("\\bfhd\\b")
    private val HD = Regex("\\bhd\\b")
    private val SD = Regex("\\bsd\\b")

    private val AUSTRIAN_ORIGIN_ALIASES = setOf(
        "orf 1", "orf1", "orf 2 steiermark", "orf2 steiermark", "orf iii", "orf 3", "orf3",
        "atv", "atv2", "atv 2", "puls 4", "puls4", "puls 24", "puls24", "servustv", "servus tv",
        "orf sport+", "orf sport +", "orf sport plus",
    )

    private val SWISS_ORIGIN_ALIASES = setOf(
        "srf 1", "srf1", "srf zwei", "srf 2", "srf2", "srf info",
        "3+", "3 plus", "4+", "4 plus", "5+", "5 plus", "6+", "6 plus",
        "tv24", "tv 24", "tv25", "tv 25",
    )
}
