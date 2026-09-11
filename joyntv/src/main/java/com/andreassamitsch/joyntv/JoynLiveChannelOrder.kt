package com.andreassamitsch.joyntv

/**
 * Orders the combined AT/DE/CH Live-TV list so the major channels of all three markets are
 * immediately visible instead of showing one complete country block before the next one.
 *
 * Only explicitly known main channels are moved. Everything else retains Joyn's original order,
 * which keeps new/niche channels stable without us having to maintain a complete channel list.
 */
internal object JoynLiveChannelOrder {
    private const val FALLBACK_RANK = 10_000

    private val mainChannelPriority = listOf(
        MainChannel(JoynCountry.AT, setOf("orf 1", "orf1")),
        MainChannel(JoynCountry.DE, setOf("das erste", "ard", "ard das erste")),
        MainChannel(JoynCountry.CH, setOf("srf 1", "srf1")),
        MainChannel(JoynCountry.AT, setOf("orf 2", "orf2")),
        MainChannel(JoynCountry.DE, setOf("zdf")),
        MainChannel(JoynCountry.CH, setOf("srf zwei", "srf 2", "srf2")),
        MainChannel(JoynCountry.AT, setOf("servustv", "servus tv")),
        MainChannel(JoynCountry.DE, setOf("rtl")),
        MainChannel(JoynCountry.CH, setOf("3+", "3 plus")),
        MainChannel(JoynCountry.AT, setOf("atv")),
        MainChannel(JoynCountry.DE, setOf("sat 1", "sat1")),
        MainChannel(JoynCountry.CH, setOf("4+", "4 plus")),
        MainChannel(JoynCountry.AT, setOf("puls 4", "puls4")),
        MainChannel(JoynCountry.DE, setOf("prosieben", "pro sieben")),
        MainChannel(JoynCountry.CH, setOf("tv24", "tv 24")),
        MainChannel(JoynCountry.AT, setOf("orf iii", "orf 3", "orf3")),
        MainChannel(JoynCountry.DE, setOf("vox")),
        MainChannel(JoynCountry.CH, setOf("srf info")),
        MainChannel(JoynCountry.DE, setOf("kabel eins", "kabeleins")),
        MainChannel(JoynCountry.DE, setOf("rtlzwei", "rtl zwei", "rtl2")),
    )

    fun sort(
        channels: List<Pair<JoynCountry, JoynLiveChannel>>,
    ): List<Pair<JoynCountry, JoynLiveChannel>> =
        channels
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<Pair<JoynCountry, JoynLiveChannel>>>(
                    { rankedChannel(it.value.first, it.value.second.title) },
                    { it.index },
                ),
            )
            .map { it.value }

    internal fun rankedChannel(country: JoynCountry, title: String): Int {
        val normalized = normalize(title)
        val rank = mainChannelPriority.indexOfFirst { main ->
            main.country == country && main.aliases.any { alias ->
                normalized == alias || normalized.startsWith("$alias ")
            }
        }
        return if (rank >= 0) rank else FALLBACK_RANK
    }

    private fun normalize(value: String): String =
        value
            .lowercase()
            .replace(Regex("\\bhd\\b"), " ")
            .replace(Regex("[^a-z0-9äöü+]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private data class MainChannel(
        val country: JoynCountry,
        val aliases: Set<String>,
    )
}
