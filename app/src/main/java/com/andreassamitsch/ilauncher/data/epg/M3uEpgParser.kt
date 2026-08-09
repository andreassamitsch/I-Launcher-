package com.andreassamitsch.ilauncher.data.epg

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object M3uEpgParser {
    private val attributeRegex = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")
    private val serviceReferenceRegex = Regex("(\\d+(?::[0-9A-Fa-f]+){9,}:?)")

    fun parse(text: String): M3uEpgSource {
        var epgUrl: String? = null
        var pending: MutableEntry? = null
        val entries = mutableListOf<MutableEntry>()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    epgUrl = attributes(line)["x-tvg-url"]
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: epgUrl
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending?.let(entries::add)
                    val splitIndex = metadataCommaIndex(line)
                    val metadata = if (splitIndex >= 0) line.substring(0, splitIndex) else line
                    val displayName = if (splitIndex >= 0) {
                        line.substring(splitIndex + 1).trim()
                    } else {
                        ""
                    }
                    val attrs = attributes(metadata)
                    val primaryId = attrs["tvg-id"]?.trim().orEmpty()
                    pending = if (primaryId.isBlank()) {
                        null
                    } else {
                        MutableEntry(
                            xmltvChannelId = primaryId,
                            alternateXmltvIds = attrs.entries
                                .filter { (key, value) ->
                                    key.startsWith("tvg-id-alt") && value.isNotBlank()
                                }
                                .map { it.value.trim() }
                                .toMutableSet(),
                            tvgName = attrs["tvg-name"]?.trim()?.takeIf(String::isNotBlank),
                            displayName = displayName.ifBlank {
                                attrs["tvg-name"]?.trim().orEmpty().ifBlank { primaryId }
                            },
                            logoUri = attrs["tvg-logo"]?.trim()?.takeIf(String::isNotBlank),
                            serviceReferenceHints = buildSet {
                                addAll(extractServiceReferences(primaryId))
                                attrs["tvg-logo"]?.let { addAll(extractServiceReferences(it)) }
                            }.toMutableSet(),
                        )
                    }
                }

                line.isNotEmpty() && !line.startsWith('#') -> {
                    pending?.let { entry ->
                        entry.serviceReferenceHints += extractServiceReferences(line)
                        entries += entry
                    }
                    pending = null
                }
            }
        }
        pending?.let(entries::add)

        val merged = linkedMapOf<String, MutableEntry>()
        entries.forEach { entry ->
            val existing = merged[entry.xmltvChannelId]
            if (existing == null) {
                merged[entry.xmltvChannelId] = entry
            } else {
                existing.alternateXmltvIds += entry.alternateXmltvIds
                existing.serviceReferenceHints += entry.serviceReferenceHints
                if (existing.tvgName.isNullOrBlank()) existing.tvgName = entry.tvgName
                if (existing.displayName.isBlank()) existing.displayName = entry.displayName
                if (existing.logoUri.isNullOrBlank()) existing.logoUri = entry.logoUri
            }
        }

        return M3uEpgSource(
            epgUrl = epgUrl,
            channels = merged.values.map { entry ->
                EpgSourceChannel(
                    xmltvChannelId = entry.xmltvChannelId,
                    alternateXmltvIds = entry.alternateXmltvIds.toList(),
                    tvgName = entry.tvgName,
                    displayName = entry.displayName,
                    logoUri = entry.logoUri,
                    serviceReferenceHints = entry.serviceReferenceHints.toList(),
                )
            },
        )
    }

    private fun attributes(line: String): Map<String, String> =
        attributeRegex.findAll(line).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }

    private fun metadataCommaIndex(line: String): Int {
        var quoted = false
        line.forEachIndexed { index, char ->
            when (char) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return index
            }
        }
        return -1
    }

    internal fun extractServiceReferences(value: String): Set<String> {
        val decoded = runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)

        return serviceReferenceRegex.findAll(decoded)
            .map { normalizeServiceReference(it.groupValues[1]) }
            .filter(String::isNotBlank)
            .toSet()
    }

    internal fun normalizeServiceReference(value: String): String =
        value.trim().trimEnd(':').uppercase()

    private data class MutableEntry(
        val xmltvChannelId: String,
        val alternateXmltvIds: MutableSet<String>,
        var tvgName: String?,
        var displayName: String,
        var logoUri: String?,
        val serviceReferenceHints: MutableSet<String>,
    )
}
