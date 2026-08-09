package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram

data class EpgSourceChannel(
    val xmltvChannelId: String,
    val alternateXmltvIds: List<String> = emptyList(),
    val tvgName: String? = null,
    val displayName: String,
    val logoUri: String? = null,
    val serviceReferenceHints: List<String> = emptyList(),
) {
    val allXmltvIds: List<String>
        get() = (listOf(xmltvChannelId) + alternateXmltvIds)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    val nameCandidates: List<String>
        get() = listOfNotNull(tvgName, displayName)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
}

data class M3uEpgSource(
    val epgUrl: String?,
    val channels: List<EpgSourceChannel>,
)

data class EpgChannelMapping(
    val serviceReference: String,
    val xmltvChannelId: String,
    val matchMethod: String,
    val confidence: Float,
)

data class XmlTvProgram(
    val xmltvChannelId: String,
    val startUtcMillis: Long,
    val stopUtcMillis: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val releaseYear: Int? = null,
    val imageUri: String? = null,
)

data class EpgState(
    val sourceUrl: String = EpgStore.DEFAULT_M3U_URL,
    val sourceLabel: String = EpgSourceUrl.label(EpgStore.DEFAULT_M3U_URL),
    val epgLabel: String? = null,
    val sourceChannels: List<EpgSourceChannel> = emptyList(),
    val mappings: List<EpgChannelMapping> = emptyList(),
    val mappingSuggestions: Map<String, List<EpgSourceChannel>> = emptyMap(),
    val enrichedChannels: List<LiveTvChannel> = emptyList(),
    val guideByServiceReference: Map<String, List<LiveTvProgram>> = emptyMap(),
    val isRefreshing: Boolean = false,
    val lastUpdatedUtcMillis: Long? = null,
    val errorMessage: String? = null,
) {
    val mappedChannelCount: Int
        get() = mappings.size

    fun guide(serviceReference: String?): List<LiveTvProgram> =
        serviceReference?.let { guideByServiceReference[it] }.orEmpty()
}
