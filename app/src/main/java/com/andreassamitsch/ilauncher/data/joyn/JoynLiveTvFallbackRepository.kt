package com.andreassamitsch.ilauncher.data.joyn

import android.content.Context
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import java.io.Closeable
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Maps only the channels of I Launcher's currently selected Gigablue bouquet to Joyn.
 *
 * The map is intentionally exact after conservative normalization. Runtime fuzzy matching is not
 * used: a wrong regional channel is worse than having no fallback. Country variants are ranked
 * deterministically, with an explicit Austria/Switzerland/Germany suffix on the Gigablue name
 * winning first and AT being the default for an otherwise neutral Austrian receiver bouquet.
 */
internal class JoynLiveTvFallbackRepository(context: Context) : Closeable {
    private val bridge = JoynPlaybackBridgeClient(context.applicationContext)
    private val mutex = Mutex()

    private var inventory: List<JoynBridgeChannel>? = null
    private var bouquetKey: String? = null
    private var bouquetChannels: List<LiveTvChannel> = emptyList()
    private var mappings: Map<String, JoynBridgeChannel> = emptyMap()

    suspend fun primeBouquet(channels: List<LiveTvChannel>): JoynFallbackMappingSnapshot = mutex.withLock {
        val currentKey = channels.joinToString("|") { "${it.serviceReference}\u0000${it.name}" }
        if (currentKey == bouquetKey && inventory != null) {
            return@withLock snapshot(channels.size)
        }

        bouquetChannels = channels
        val available = inventory ?: bridge.listChannels().also { inventory = it }
        mappings = JoynLiveChannelMatcher.mapBouquet(channels, available)
        bouquetKey = currentKey
        snapshot(channels.size)
    }

    suspend fun resolve(channel: LiveTvChannel): JoynFallbackPlayback? {
        val mapped = mutex.withLock {
            mappings[channel.serviceReference] ?: run {
                // The initial background inventory request may still have been unavailable when the
                // player started. Retry lazily on a real SAT failure before giving up on Joyn.
                val available = inventory ?: bridge.listChannels().also { inventory = it }
                val source = if (bouquetChannels.isEmpty()) listOf(channel) else bouquetChannels
                mappings = JoynLiveChannelMatcher.mapBouquet(source, available)
                bouquetKey = source.joinToString("|") { "${it.serviceReference}\u0000${it.name}" }
                mappings[channel.serviceReference]
            }
        } ?: return null
        return bridge.resolvePlayback(mapped)
    }

    fun mappedChannel(serviceReference: String): JoynBridgeChannel? = mappings[serviceReference]

    fun releasePlayback() = bridge.releasePlayback()

    override fun close() = bridge.close()

    private fun snapshot(bouquetSize: Int): JoynFallbackMappingSnapshot =
        JoynFallbackMappingSnapshot(
            bouquetSize = bouquetSize,
            mappedByServiceReference = mappings.toMap(),
        )
}

internal data class JoynFallbackMappingSnapshot(
    val bouquetSize: Int,
    val mappedByServiceReference: Map<String, JoynBridgeChannel>,
) {
    val mappedCount: Int get() = mappedByServiceReference.size
}

internal object JoynLiveChannelMatcher {
    private val strippedWords = setOf(
        "hd", "uhd", "sd",
        "austria", "osterreich", "oesterreich", "at",
        "schweiz", "suisse", "svizzera", "switzerland", "ch",
        "deutschland", "germany", "de",
    )

    private val aliases = mapOf(
        "pro7" to "prosieben",
        "prosieben" to "prosieben",
        "kabel1" to "kabeleins",
        "kabeleins" to "kabeleins",
        "rtlzwei" to "rtl2",
        "rtl2" to "rtl2",
        "daserste" to "daserste",
        "arddaserste" to "daserste",
    )

    fun mapBouquet(
        bouquet: List<LiveTvChannel>,
        joynChannels: List<JoynBridgeChannel>,
    ): Map<String, JoynBridgeChannel> {
        val byCore = joynChannels.groupBy { canonicalCore(it.title) }
        return buildMap {
            bouquet.forEach { satChannel ->
                val core = canonicalCore(satChannel.name)
                if (core.isBlank()) return@forEach
                val candidates = byCore[core].orEmpty()
                if (candidates.isEmpty()) return@forEach
                val countryOrder = countryPriority(satChannel.name)
                val selected = candidates.minWithOrNull(
                    compareBy<JoynBridgeChannel> { candidate ->
                        val index = countryOrder.indexOf(candidate.country.uppercase(Locale.US))
                        if (index >= 0) index else Int.MAX_VALUE
                    }.thenBy { it.title.length },
                ) ?: return@forEach
                put(satChannel.serviceReference, selected)
            }
        }
    }

    internal fun canonicalCore(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.GERMAN)
            .replace("ß", "ss")
            .replace("+", " plus ")
        val words = ascii
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in strippedWords }
        val compact = words.joinToString("")
        return aliases[compact] ?: compact
    }

    internal fun countryPriority(value: String): List<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.GERMAN)
        val preferred = when {
            Regex("\\b(schweiz|suisse|svizzera|switzerland|ch)\\b").containsMatchIn(normalized) -> "CH"
            Regex("\\b(deutschland|germany|de)\\b").containsMatchIn(normalized) -> "DE"
            Regex("\\b(austria|osterreich|oesterreich|at)\\b").containsMatchIn(normalized) -> "AT"
            else -> "AT"
        }
        return buildList {
            add(preferred)
            listOf("AT", "CH", "DE").forEach { if (it !in this) add(it) }
        }
    }
}
