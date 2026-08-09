package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import kotlin.math.abs

internal data class EpgMergeResult(
    val channels: List<LiveTvChannel>,
    val guideByServiceReference: Map<String, List<LiveTvProgram>>,
)

internal object EpgMerger {
    private const val START_TOLERANCE_MILLIS = 20L * 60L * 1_000L

    fun merge(
        channels: List<LiveTvChannel>,
        mappings: List<EpgChannelMapping>,
        programmes: List<XmlTvProgram>,
        nowUtcMillis: Long,
    ): EpgMergeResult {
        val mappingByServiceRef = mappings.associateBy(EpgChannelMapping::serviceReference)
        val programmesByChannel = programmes
            .groupBy(XmlTvProgram::xmltvChannelId)
            .mapValues { (_, values) -> values.sortedBy(XmlTvProgram::startUtcMillis) }
        val guide = linkedMapOf<String, List<LiveTvProgram>>()

        val enriched = channels.map { channel ->
            val mapping = mappingByServiceRef[channel.serviceReference] ?: return@map channel
            val xmlPrograms = programmesByChannel[mapping.xmltvChannelId].orEmpty()
            if (xmlPrograms.isEmpty()) return@map channel

            guide[channel.serviceReference] = xmlPrograms.map(XmlTvProgram::toLiveTvProgram)

            val xmlNow = xmlPrograms.firstOrNull { programme ->
                nowUtcMillis >= programme.startUtcMillis && nowUtcMillis < programme.stopUtcMillis
            }
            val xmlNext = xmlPrograms.firstOrNull { programme ->
                programme.startUtcMillis >= (xmlNow?.stopUtcMillis ?: nowUtcMillis)
            }

            val mergedNow = when {
                channel.now == null -> xmlNow?.toLiveTvProgram()
                xmlNow != null && sameSlot(channel.now, xmlNow) -> mergeMetadata(channel.now, xmlNow)
                else -> channel.now
            }
            val mergedNext = when {
                channel.next == null -> xmlNext?.toLiveTvProgram()
                else -> {
                    val matching = xmlPrograms.firstOrNull { sameSlot(channel.next, it) }
                    if (matching != null) mergeMetadata(channel.next, matching) else channel.next
                }
            }

            channel.copy(now = mergedNow, next = mergedNext)
        }

        return EpgMergeResult(
            channels = enriched,
            guideByServiceReference = guide,
        )
    }

    private fun sameSlot(base: LiveTvProgram, xml: XmlTvProgram): Boolean {
        val startClose = abs(base.startUtcMillis - xml.startUtcMillis) <= START_TOLERANCE_MILLIS
        val overlap = minOf(base.endUtcMillis, xml.stopUtcMillis) -
            maxOf(base.startUtcMillis, xml.startUtcMillis)
        val titleEqual = EpgChannelMatcher.normalizeName(base.title) ==
            EpgChannelMatcher.normalizeName(xml.title)
        return startClose || (titleEqual && overlap > 0L)
    }

    private fun mergeMetadata(base: LiveTvProgram, xml: XmlTvProgram): LiveTvProgram = base.copy(
        subtitle = base.subtitle ?: xml.subtitle,
        shortDescription = base.shortDescription ?: xml.description,
        longDescription = base.longDescription ?: xml.description,
        categories = base.categories?.takeIf { it.isNotEmpty() } ?: xml.categories,
        seasonNumber = base.seasonNumber ?: xml.seasonNumber,
        episodeNumber = base.episodeNumber ?: xml.episodeNumber,
        releaseYear = base.releaseYear ?: xml.releaseYear,
        imageUri = base.imageUri ?: xml.imageUri,
        xmltvChannelId = xml.xmltvChannelId,
    )

    internal fun XmlTvProgram.toLiveTvProgram(): LiveTvProgram = LiveTvProgram(
        eventId = null,
        title = title,
        shortDescription = description,
        longDescription = description,
        startUtcMillis = startUtcMillis,
        durationMillis = (stopUtcMillis - startUtcMillis).coerceAtLeast(0L),
        subtitle = subtitle,
        categories = categories,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        releaseYear = releaseYear,
        imageUri = imageUri,
        xmltvChannelId = xmltvChannelId,
    )
}
