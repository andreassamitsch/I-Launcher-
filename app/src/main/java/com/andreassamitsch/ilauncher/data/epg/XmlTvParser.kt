package com.andreassamitsch.ilauncher.data.epg

import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

internal object XmlTvParser {
    private const val MAX_MATCHED_PROGRAMMES = 50_000

    fun parse(
        input: InputStream,
        interestedChannelIds: Set<String>,
        windowStartUtcMillis: Long,
        windowEndUtcMillis: Long,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): List<XmlTvProgram> {
        if (interestedChannelIds.isEmpty()) return emptyList()
        val handler = Handler(
            interestedChannelIds = interestedChannelIds,
            windowStartUtcMillis = windowStartUtcMillis,
            windowEndUtcMillis = windowEndUtcMillis,
            defaultZone = defaultZone,
        )
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        factory.newSAXParser().parse(input, handler)
        return handler.programmes
    }

    private class Handler(
        private val interestedChannelIds: Set<String>,
        private val windowStartUtcMillis: Long,
        private val windowEndUtcMillis: Long,
        private val defaultZone: ZoneId,
    ) : DefaultHandler() {
        val programmes = mutableListOf<XmlTvProgram>()
        private var current: MutableProgramme? = null
        private var capture: Capture? = null

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes,
        ) {
            val tag = tag(localName, qName)
            if (tag == "programme") {
                val channel = attributes.value("channel") ?: return
                if (channel !in interestedChannelIds) return
                val start = XmlTvTime.parse(attributes.value("start"), defaultZone) ?: return
                val stop = XmlTvTime.parse(attributes.value("stop"), defaultZone) ?: return
                if (stop <= windowStartUtcMillis || start >= windowEndUtcMillis || stop <= start) return
                current = MutableProgramme(channel, start, stop)
                return
            }

            val programme = current ?: return
            when (tag) {
                "title", "sub-title", "desc", "category", "episode-num", "date" -> {
                    capture = Capture(
                        tag = tag,
                        language = attributes.value("lang"),
                        system = attributes.value("system"),
                    )
                }

                "icon" -> if (programme.imageUri.isNullOrBlank()) {
                    programme.imageUri = attributes.value("src")?.trim()?.takeIf(String::isNotBlank)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            capture?.text?.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = tag(localName, qName)
            val programme = current
            val activeCapture = capture
            if (programme != null && activeCapture != null && activeCapture.tag == tag) {
                val value = activeCapture.text.toString().trim()
                if (value.isNotBlank()) {
                    when (tag) {
                        "title" -> programme.titles += LocalizedText(value, activeCapture.language)
                        "sub-title" -> programme.subtitles += LocalizedText(value, activeCapture.language)
                        "desc" -> programme.descriptions += LocalizedText(value, activeCapture.language)
                        "category" -> programme.categories += value
                        "episode-num" -> programme.episodeNumbers += EpisodeNumber(
                            system = activeCapture.system,
                            value = value,
                        )
                        "date" -> if (programme.releaseYear == null) {
                            programme.releaseYear = value.take(4).toIntOrNull()
                        }
                    }
                }
                capture = null
            }

            if (tag == "programme" && programme != null) {
                val title = preferredText(programme.titles)
                if (!title.isNullOrBlank()) {
                    val parsedEpisode = parseEpisode(programme.episodeNumbers)
                    programmes += XmlTvProgram(
                        xmltvChannelId = programme.channelId,
                        startUtcMillis = programme.startUtcMillis,
                        stopUtcMillis = programme.stopUtcMillis,
                        title = title,
                        subtitle = preferredText(programme.subtitles),
                        description = preferredText(programme.descriptions),
                        categories = programme.categories.distinct(),
                        seasonNumber = parsedEpisode?.first,
                        episodeNumber = parsedEpisode?.second,
                        releaseYear = programme.releaseYear,
                        imageUri = programme.imageUri,
                    )
                    check(programmes.size <= MAX_MATCHED_PROGRAMMES) {
                        "XMLTV matched programme limit exceeded"
                    }
                }
                current = null
                capture = null
            }
        }

        private fun tag(localName: String?, qName: String?): String =
            localName?.takeIf(String::isNotBlank) ?: qName.orEmpty()

        private fun Attributes.value(name: String): String? =
            getValue(name) ?: getValue("", name)
    }

    private fun preferredText(values: List<LocalizedText>): String? = values
        .minByOrNull { candidate ->
            when (candidate.language?.lowercase()) {
                "de", "de-de", "de-at" -> 0
                null, "" -> 1
                "en", "en-us", "en-gb" -> 2
                else -> 3
            }
        }
        ?.value

    private fun parseEpisode(values: List<EpisodeNumber>): Pair<Int, Int>? {
        values.firstOrNull { it.system.equals("xmltv_ns", ignoreCase = true) }
            ?.let { parseXmlTvNs(it.value) }
            ?.let { return it }
        values.firstOrNull { it.system.equals("onscreen", ignoreCase = true) }
            ?.let { parseOnScreen(it.value) }
            ?.let { return it }
        values.asSequence().mapNotNull { parseOnScreen(it.value) }.firstOrNull()?.let { return it }
        return null
    }

    private fun parseXmlTvNs(value: String): Pair<Int, Int>? {
        val parts = value.trim().split('.')
        if (parts.size < 2) return null
        val seasonZeroBased = parts[0].substringBefore('/').trim().toIntOrNull() ?: return null
        val episodeZeroBased = parts[1].substringBefore('/').trim().toIntOrNull() ?: return null
        return (seasonZeroBased + 1) to (episodeZeroBased + 1)
    }

    private fun parseOnScreen(value: String): Pair<Int, Int>? {
        Regex("(?i)S\\s*(\\d{1,3})\\s*[._:-]?\\s*E\\s*(\\d{1,4})")
            .find(value)
            ?.let { match ->
                return match.groupValues[1].toInt() to match.groupValues[2].toInt()
            }
        Regex("^(\\d{1,3})[./-](\\d{1,4})(?:\\D|$)")
            .find(value.trim())
            ?.let { match ->
                return match.groupValues[1].toInt() to match.groupValues[2].toInt()
            }
        return null
    }

    private data class MutableProgramme(
        val channelId: String,
        val startUtcMillis: Long,
        val stopUtcMillis: Long,
        val titles: MutableList<LocalizedText> = mutableListOf(),
        val subtitles: MutableList<LocalizedText> = mutableListOf(),
        val descriptions: MutableList<LocalizedText> = mutableListOf(),
        val categories: MutableList<String> = mutableListOf(),
        val episodeNumbers: MutableList<EpisodeNumber> = mutableListOf(),
        var releaseYear: Int? = null,
        var imageUri: String? = null,
    )

    private data class LocalizedText(val value: String, val language: String?)
    private data class EpisodeNumber(val system: String?, val value: String)
    private data class Capture(
        val tag: String,
        val language: String?,
        val system: String?,
        val text: StringBuilder = StringBuilder(),
    )
}

internal object XmlTvTime {
    private val pattern = Regex("^(\\d{8}|\\d{12}|\\d{14})(?:\\s*([+-]\\d{4}|Z))?")
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(raw: String?, defaultZone: ZoneId = ZoneId.systemDefault()): Long? {
        val match = raw?.trim()?.let(pattern::find) ?: return null
        val value = match.groupValues[1]
        val digits = when (value.length) {
            8 -> value + "000000"
            12 -> value + "00"
            14 -> value
            else -> return null
        }
        val local = runCatching { LocalDateTime.parse(digits, formatter) }.getOrNull() ?: return null
        val zoneToken = match.groupValues.getOrNull(2).orEmpty()
        return if (zoneToken.isBlank()) {
            local.atZone(defaultZone).toInstant().toEpochMilli()
        } else {
            val offset = if (zoneToken == "Z") {
                ZoneOffset.UTC
            } else {
                runCatching {
                    ZoneOffset.of(zoneToken.substring(0, 3) + ":" + zoneToken.substring(3))
                }.getOrNull() ?: return null
            }
            local.toInstant(offset).toEpochMilli()
        }
    }
}
