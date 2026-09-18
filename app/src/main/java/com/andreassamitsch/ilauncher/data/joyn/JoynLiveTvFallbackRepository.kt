package com.andreassamitsch.ilauncher.data.joyn

import android.content.Context
import android.os.SystemClock
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import java.io.Closeable
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Maps only the channels of I Launcher's currently selected Gigablue bouquet to Joyn.
 *
 * The map is intentionally exact after conservative normalization. Runtime fuzzy matching is not
 * used: a wrong regional channel is worse than having no fallback. For selected stations whose
 * Swiss Joyn feed is known to offer the better TV quality, CH is preferred when that exact station
 * is available there. CH is therefore the first choice for every exact station family whenever
 * Joyn exposes a Swiss variant; AT and DE are only fallbacks.
 *
 * Playback resolution can be prewarmed while SAT is still running. The resolved manifest, DRM URL
 * and loopback bridge stay cached for the current channel session so a later manual or automatic
 * SAT -> Joyn switch does not have to perform country routing and entitlement from scratch.
 */
internal class JoynLiveTvFallbackRepository(context: Context) : Closeable {
    private val bridge = JoynPlaybackBridgeClient(context.applicationContext)
    private val mutex = Mutex()
    private val playbackMutex = Mutex()
    private val playbackGeneration = AtomicLong(0L)

    private var inventory: List<JoynBridgeChannel>? = null
    private var inventoryLoadedAtElapsedMs: Long = 0L
    private var bouquetKey: String? = null
    private var bouquetChannels: List<LiveTvChannel> = emptyList()
    private var mappings: Map<String, JoynBridgeChannel> = emptyMap()

    @Volatile
    private var preparedPlayback: PreparedPlayback? = null

    suspend fun primeBouquet(channels: List<LiveTvChannel>): JoynFallbackMappingSnapshot = mutex.withLock {
        val currentKey = channels.joinToString("|") { "${it.serviceReference}\u0000${it.name}" }
        val inventoryFresh = inventory != null &&
            SystemClock.elapsedRealtime() - inventoryLoadedAtElapsedMs < INVENTORY_TTL_MS
        if (currentKey == bouquetKey && inventoryFresh) {
            return@withLock snapshot(channels.size)
        }

        bouquetChannels = channels
        val available = if (inventoryFresh) {
            requireNotNull(inventory)
        } else {
            loadInventoryPreferSwiss()
        }
        mappings = JoynLiveChannelMatcher.mapBouquet(channels, available)
        bouquetKey = currentKey
        snapshot(channels.size)
    }

    private suspend fun loadInventoryPreferSwiss(): List<JoynBridgeChannel> {
        var loaded = bridge.listChannels()
        // A whole-country routing failure can yield a valid but partial AT/DE inventory. Because CH
        // is our quality-first source, retry the inventory once when no Swiss rows arrived at all.
        if (loaded.none { it.country.equals("CH", ignoreCase = true) }) {
            runCatching { bridge.listChannels() }
                .getOrNull()
                ?.takeIf { retry -> retry.any { it.country.equals("CH", ignoreCase = true) } }
                ?.let { loaded = it }
        }
        inventory = loaded
        inventoryLoadedAtElapsedMs = SystemClock.elapsedRealtime()
        return loaded
    }

    /** Resolve and hold the current channel's Joyn route before SAT actually needs it. */
    suspend fun prewarm(channel: LiveTvChannel): JoynFallbackPlayback? = resolve(channel)

    suspend fun resolve(channel: LiveTvChannel): JoynFallbackPlayback? {
        val mapped = mappedFor(channel) ?: return null
        val generationAtStart = playbackGeneration.get()

        return playbackMutex.withLock {
            if (generationAtStart != playbackGeneration.get()) return@withLock null

            preparedPlayback
                ?.takeIf {
                    it.serviceReference == channel.serviceReference &&
                        it.channelId == mapped.id &&
                        it.generation == generationAtStart
                }
                ?.playback
                ?.let { return@withLock it }

            val resolved = bridge.resolvePlayback(mapped)
            if (generationAtStart != playbackGeneration.get()) {
                // The user zapped while the remote preparation was still running. Close that stale
                // bridge before the next channel is allowed to prepare its own route.
                bridge.releasePlayback()
                return@withLock null
            }

            preparedPlayback = PreparedPlayback(
                serviceReference = channel.serviceReference,
                channelId = mapped.id,
                generation = generationAtStart,
                playback = resolved,
            )
            resolved
        }
    }

    private suspend fun mappedFor(channel: LiveTvChannel): JoynBridgeChannel? = mutex.withLock {
        val current = mappings[channel.serviceReference]
        val inventoryStale = inventory == null ||
            SystemClock.elapsedRealtime() - inventoryLoadedAtElapsedMs >= INVENTORY_TTL_MS

        if (current != null && (!inventoryStale || current.country.equals("CH", ignoreCase = true))) {
            return@withLock current
        }

        // Retry lazily when no mapping exists or when a non-CH mapping has outlived the inventory
        // TTL. This prevents a temporary Swiss inventory failure from pinning an AT/DE choice for
        // the rest of a long-running launcher process.
        val available = if (inventoryStale) loadInventoryPreferSwiss() else requireNotNull(inventory)
        val source = if (bouquetChannels.isEmpty()) listOf(channel) else bouquetChannels
        mappings = JoynLiveChannelMatcher.mapBouquet(source, available)
        bouquetKey = source.joinToString("|") { "${it.serviceReference}\u0000${it.name}" }
        mappings[channel.serviceReference]
    }

    fun mappedChannel(serviceReference: String): JoynBridgeChannel? = mappings[serviceReference]

    fun releasePlayback() {
        playbackGeneration.incrementAndGet()
        preparedPlayback = null
        bridge.releasePlayback()
    }

    override fun close() {
        releasePlayback()
        bridge.close()
    }

    private fun snapshot(bouquetSize: Int): JoynFallbackMappingSnapshot =
        JoynFallbackMappingSnapshot(
            bouquetSize = bouquetSize,
            mappedByServiceReference = mappings.toMap(),
        )

    private data class PreparedPlayback(
        val serviceReference: String,
        val channelId: String,
        val generation: Long,
        val playback: JoynFallbackPlayback,
    )
}

internal data class JoynFallbackMappingSnapshot(
    val bouquetSize: Int,
    val mappedByServiceReference: Map<String, JoynBridgeChannel>,
) {
    val mappedCount: Int get() = mappedByServiceReference.size
}

private const val INVENTORY_TTL_MS = 10L * 60L * 1000L

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
                val countryOrder = countryPriority(satChannel.name, core)
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

    internal fun countryPriority(value: String, core: String = canonicalCore(value)): List<String> {
        // The bouquet name/core has already been used for the exact station-family match above.
        // Regional suffixes must not lower the quality preference.
        return listOf("CH", "AT", "DE")
    }
}
