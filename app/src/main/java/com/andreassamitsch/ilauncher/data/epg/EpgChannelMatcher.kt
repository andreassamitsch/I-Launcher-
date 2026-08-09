package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import java.text.Normalizer
import kotlin.math.max

internal object EpgChannelMatcher {
    const val METHOD_MANUAL = "manual"
    const val METHOD_SERVICE_REFERENCE = "service_reference"
    const val METHOD_EXACT_NAME = "exact_name"
    const val METHOD_FUZZY_NAME = "fuzzy_name"
    const val METHOD_ALT_XMLTV_ID = "alternate_xmltv_id"

    fun autoMappings(
        channels: List<LiveTvChannel>,
        sourceChannels: List<EpgSourceChannel>,
        manualMappings: List<EpgChannelMapping>,
    ): List<EpgChannelMapping> {
        val manualRefs = manualMappings
            .filter { it.matchMethod == METHOD_MANUAL }
            .map { it.serviceReference }
            .toSet()

        return channels.mapNotNull { channel ->
            if (channel.serviceReference in manualRefs) return@mapNotNull null
            match(channel, sourceChannels)
        }
    }

    fun suggestions(
        channelName: String,
        sourceChannels: List<EpgSourceChannel>,
        limit: Int = 12,
    ): List<EpgSourceChannel> = sourceChannels
        .map { source -> source to nameSimilarity(channelName, source) }
        .sortedWith(
            compareByDescending<Pair<EpgSourceChannel, Float>> { it.second }
                .thenBy { it.first.displayName.lowercase() },
        )
        .take(limit)
        .map { it.first }

    private fun match(
        channel: LiveTvChannel,
        sourceChannels: List<EpgSourceChannel>,
    ): EpgChannelMapping? {
        val normalizedReference = M3uEpgParser.normalizeServiceReference(channel.serviceReference)
        val referenceMatches = sourceChannels.filter { source ->
            source.serviceReferenceHints.any { hint ->
                M3uEpgParser.normalizeServiceReference(hint) == normalizedReference
            }
        }
        if (referenceMatches.size == 1) {
            return EpgChannelMapping(
                serviceReference = channel.serviceReference,
                xmltvChannelId = referenceMatches.single().xmltvChannelId,
                matchMethod = METHOD_SERVICE_REFERENCE,
                confidence = 1f,
            )
        }

        val normalizedName = normalizeName(channel.name)
        val exactMatches = sourceChannels.filter { source ->
            source.nameCandidates.any { normalizeName(it) == normalizedName }
        }
        if (exactMatches.size == 1) {
            return EpgChannelMapping(
                serviceReference = channel.serviceReference,
                xmltvChannelId = exactMatches.single().xmltvChannelId,
                matchMethod = METHOD_EXACT_NAME,
                confidence = 0.98f,
            )
        }

        val ranked = sourceChannels
            .map { source -> source to nameSimilarity(channel.name, source) }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0f
        if (best.second < 0.94f || best.second - second < 0.06f) return null

        return EpgChannelMapping(
            serviceReference = channel.serviceReference,
            xmltvChannelId = best.first.xmltvChannelId,
            matchMethod = METHOD_FUZZY_NAME,
            confidence = best.second,
        )
    }

    private fun nameSimilarity(channelName: String, source: EpgSourceChannel): Float =
        source.nameCandidates.maxOfOrNull { candidate ->
            similarity(normalizeName(channelName), normalizeName(candidate))
        } ?: 0f

    internal fun normalizeName(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", "und")
            .replace("+", "plus")
        val tokens = decomposed
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it !in setOf("hd", "uhd", "sd") }
        return tokens.joinToString(separator = "")
            .replace("prosieben", "pro7")
            .replace("kabeleins", "kabel1")
            .replace("orfiii", "orf3")
    }

    private fun similarity(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 1f
        val distance = levenshtein(left, right)
        return 1f - distance.toFloat() / max(left.length, right.length).toFloat()
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                val insertion = current[rightIndex] + 1
                val deletion = previous[rightIndex + 1] + 1
                val substitution = previous[rightIndex] + if (leftChar == rightChar) 0 else 1
                current[rightIndex + 1] = minOf(insertion, deletion, substitution)
            }
            previous = current
        }
        return previous[right.length]
    }
}
