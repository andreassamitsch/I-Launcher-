package com.andreassamitsch.joyntv

/**
 * Orders the combined AT/DE/CH Live-TV list in two clear blocks:
 *
 * 1. main channels grouped by country: AT, then DE, then CH
 * 2. all additional channels grouped by country: AT, then DE, then CH
 *
 * Duplicate channel brands exposed by more than one Joyn market are removed. For known brands the
 * original market wins (for example ProSieben/SAT.1 -> DE, ServusTV -> AT, SRF -> CH). Unknown
 * duplicates prefer DE, which is the usual origin for channels mirrored into AT/CH.
 */
internal object JoynLiveChannelOrder {
    private const val FALLBACK_RANK = 10_000

    private val countryOrder = listOf(JoynCountry.AT, JoynCountry.DE, JoynCountry.CH)

    private val mainChannelsByCountry = mapOf(
        JoynCountry.AT to listOf(
            setOf("orf 1", "orf1"),
            setOf("orf 2", "orf2"),
            setOf("servustv", "servus tv"),
            setOf("atv"),
            setOf("puls 4", "puls4"),
            setOf("orf iii", "orf 3", "orf3"),
        ),
        JoynCountry.DE to listOf(
            setOf("das erste", "ard", "ard das erste"),
            setOf("zdf"),
            setOf("rtl"),
            setOf("sat 1", "sat1"),
            setOf("prosieben", "pro sieben"),
            setOf("vox"),
            setOf("kabel eins", "kabeleins"),
            setOf("rtlzwei", "rtl zwei", "rtl2"),
        ),
        JoynCountry.CH to listOf(
            setOf("srf 1", "srf1"),
            setOf("srf zwei", "srf 2", "srf2"),
            setOf("3+", "3 plus"),
            setOf("4+", "4 plus"),
            setOf("tv24", "tv 24"),
            setOf("srf info"),
        ),
    )

    fun sort(
        channels: List<Pair<JoynCountry, JoynLiveChannel>>,
    ): List<Pair<JoynCountry, JoynLiveChannel>> {
        val deduplicated = deduplicate(channels)

        val mainChannels = countryOrder.flatMap { country ->
            deduplicated
                .withIndex()
                .filter { it.value.first == country && rankedChannel(country, it.value.second.title) < FALLBACK_RANK }
                .sortedWith(
                    compareBy<IndexedValue<Pair<JoynCountry, JoynLiveChannel>>>(
                        { rankedChannel(country, it.value.second.title) },
                        { it.index },
                    ),
                )
                .map { it.value }
        }

        val additionalChannels = countryOrder.flatMap { country ->
            deduplicated.filter { (channelCountry, channel) ->
                channelCountry == country && rankedChannel(country, channel.title) == FALLBACK_RANK
            }
        }

        return mainChannels + additionalChannels
    }

    internal fun rankedChannel(country: JoynCountry, title: String): Int {
        val normalized = normalize(title)
        val priorities = mainChannelsByCountry[country].orEmpty()
        val rank = priorities.indexOfFirst { aliases ->
            aliases.any { alias -> normalized == alias || normalized.startsWith("$alias ") }
        }
        return if (rank >= 0) rank else FALLBACK_RANK
    }

    private fun deduplicate(
        channels: List<Pair<JoynCountry, JoynLiveChannel>>,
    ): List<Pair<JoynCountry, JoynLiveChannel>> {
        val grouped = channels.groupBy { (_, channel) -> duplicateKey(channel.title) }
        val winners = grouped.values.map { duplicates ->
            if (duplicates.size == 1) return@map duplicates.first()

            val preferredCountry = preferredOrigin(duplicateKey(duplicates.first().second.title))
            duplicates.firstOrNull { it.first == preferredCountry }
                ?: duplicateFallbackOrder.firstNotNullOfOrNull { country -> duplicates.firstOrNull { it.first == country } }
                ?: duplicates.first()
        }.toSet()

        return channels.filter { it in winners }
    }

    private fun duplicateKey(title: String): String {
        var normalized = normalize(title)
        REGION_SUFFIXES.forEach { suffix ->
            if (normalized.endsWith(" $suffix")) {
                normalized = normalized.removeSuffix(" $suffix").trim()
            }
        }
        return normalized
    }

    private fun preferredOrigin(channelKey: String): JoynCountry = when {
        matchesAny(channelKey, AUSTRIAN_ORIGIN_ALIASES) -> JoynCountry.AT
        matchesAny(channelKey, SWISS_ORIGIN_ALIASES) -> JoynCountry.CH
        matchesAny(channelKey, GERMAN_ORIGIN_ALIASES) -> JoynCountry.DE
        else -> JoynCountry.DE
    }

    private fun matchesAny(value: String, aliases: Set<String>): Boolean =
        aliases.any { alias -> value == alias || value.startsWith("$alias ") }

    private fun normalize(value: String): String =
        value
            .lowercase()
            .replace(Regex("\\b(?:hd|uhd|sd)\\b"), " ")
            .replace(Regex("[^a-z0-9äöü+]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private val duplicateFallbackOrder = listOf(JoynCountry.DE, JoynCountry.AT, JoynCountry.CH)

    private val REGION_SUFFIXES = setOf(
        "österreich",
        "austria",
        "at",
        "deutschland",
        "germany",
        "de",
        "schweiz",
        "switzerland",
        "ch",
    )

    private val AUSTRIAN_ORIGIN_ALIASES = setOf(
        "orf 1", "orf1", "orf 2", "orf2", "orf iii", "orf 3", "orf3",
        "atv", "atv2", "puls 4", "puls4", "puls 24", "puls24", "servustv", "servus tv",
    )

    private val GERMAN_ORIGIN_ALIASES = setOf(
        "das erste", "ard", "zdf", "rtl", "sat 1", "sat1", "prosieben", "pro sieben",
        "vox", "kabel eins", "kabeleins", "rtlzwei", "rtl zwei", "rtl2", "sixx",
        "prosieben maxx", "pro sieben maxx", "sat 1 gold", "sat1 gold", "kabel eins doku",
        "kabeleins doku", "welt", "n24 doku", "dmax", "tlc",
    )

    private val SWISS_ORIGIN_ALIASES = setOf(
        "srf 1", "srf1", "srf zwei", "srf 2", "srf2", "srf info",
        "3+", "3 plus", "4+", "4 plus", "tv24", "tv 24", "tv25", "tv 25",
    )
}
