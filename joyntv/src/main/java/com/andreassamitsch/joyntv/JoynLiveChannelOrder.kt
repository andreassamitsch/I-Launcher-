package com.andreassamitsch.joyntv

/**
 * Curated combined AT/DE/CH Live-TV list.
 *
 * The TV app deliberately does not expose every thematic/event/FAST stream Joyn happens to return.
 * It keeps the well-known general-interest channels in a stable order: Austria first, then Germany,
 * then Switzerland. Mirrored brands from AT/CH are omitted when the German original is available.
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

    internal fun rankedChannel(country: JoynCountry, title: String): Int {
        val normalized = rankingKey(title)
        val priorities = popularChannelsByCountry[country].orEmpty()
        val rank = priorities.indexOfFirst { aliases -> normalized in aliases }
        return if (rank >= 0) rank else NOT_LISTED
    }

    private fun deduplicate(
        channels: List<Pair<JoynCountry, JoynLiveChannel>>,
    ): List<Pair<JoynCountry, JoynLiveChannel>> {
        val grouped = channels.groupBy { (_, channel) -> duplicateKey(channel.title) }
        val winners = grouped.values.map { duplicates ->
            if (duplicates.size == 1) return@map duplicates.first()

            val key = duplicateKey(duplicates.first().second.title)
            val preferredCountry = preferredOrigin(key)
            duplicates.firstOrNull { it.first == preferredCountry }
                ?: countryOrder.firstNotNullOfOrNull { country -> duplicates.firstOrNull { it.first == country } }
                ?: duplicates.first()
        }.toSet()
        return channels.filter { it in winners }
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
            .replace(Regex("\\b(?:hd|uhd|sd)\\b"), " ")
            .replace(Regex("[^a-z0-9äöü+]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private val REGION_SUFFIXES = setOf(
        "österreich", "austria", "at",
        "deutschland", "germany", "de",
        "schweiz", "switzerland", "ch",
    )

    private val STREAM_SUFFIXES = setOf("live", "livestream")

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
