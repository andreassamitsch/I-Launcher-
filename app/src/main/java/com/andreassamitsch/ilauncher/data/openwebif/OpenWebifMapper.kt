package com.andreassamitsch.ilauncher.data.openwebif

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram

internal object OpenWebifMapper {
    private val numericEntity = Regex("&#(x[0-9A-Fa-f]+|[0-9]+);")
    private val namedEntity = Regex("&(amp|quot|apos|lt|gt|nbsp);", RegexOption.IGNORE_CASE)
    private val inlineWhitespace = Regex("\\s+")
    private val repeatedBlankLines = Regex("\\n{3,}")

    fun bouquets(services: List<OpenWebifServiceDto>): List<OpenWebifBouquet> =
        services.mapNotNull { service ->
            val name = service.serviceName?.cleanInline()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val reference = service.serviceReference?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            OpenWebifBouquet(name = name, serviceReference = reference)
        }

    fun channels(
        baseUrl: String,
        services: List<OpenWebifServiceDto>,
        events: List<OpenWebifEventDto>,
        nowUtcMillis: Long = System.currentTimeMillis(),
    ): List<LiveTvChannel> {
        val nowSeconds = nowUtcMillis / 1_000L
        return services.mapNotNull { service ->
            if (service.pos == 0) return@mapNotNull null
            val reference = service.serviceReference?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = service.serviceName?.cleanInline()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val matchingEvents = events
                .filter { event -> referencesMatch(reference, event.sref) }
                .sortedBy { it.beginTimestamp ?: Long.MAX_VALUE }

            val currentDto = matchingEvents.firstOrNull { event ->
                val start = event.beginTimestamp ?: return@firstOrNull false
                val duration = event.durationSec ?: 0L
                duration > 0L && nowSeconds >= start && nowSeconds < start + duration
            } ?: matchingEvents.firstOrNull()

            val currentIndex = currentDto?.let(matchingEvents::indexOf) ?: -1
            val nextDto = if (currentIndex >= 0) {
                matchingEvents.drop(currentIndex + 1).firstOrNull()
            } else {
                null
            }

            LiveTvChannel(
                serviceReference = reference,
                name = name,
                piconUri = OpenWebifUrl.resolve(baseUrl, service.picon),
                now = currentDto?.toProgram(),
                next = nextDto?.toProgram(),
            )
        }
    }

    private fun OpenWebifEventDto.toProgram(): LiveTvProgram? {
        val start = beginTimestamp ?: return null
        val duration = durationSec ?: return null
        val programTitle = title?.cleanInline()?.takeIf { it.isNotBlank() } ?: return null
        return LiveTvProgram(
            eventId = id,
            title = programTitle,
            shortDescription = shortdesc?.cleanMultiline()?.takeIf { it.isNotBlank() },
            longDescription = longdesc?.cleanMultiline()?.takeIf { it.isNotBlank() },
            startUtcMillis = start * 1_000L,
            durationMillis = duration.coerceAtLeast(0L) * 1_000L,
        )
    }

    private fun String.cleanInline(): String = decodeEntities(this)
        .replace("\\r\\n", " ")
        .replace("\\n", " ")
        .replace("\\r", " ")
        .replace(inlineWhitespace, " ")
        .trim()

    private fun String.cleanMultiline(): String = decodeEntities(this)
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\r", "\n")
        .lines()
        .joinToString("\n") { line -> line.replace(Regex("[\\t ]+"), " ").trim() }
        .replace(repeatedBlankLines, "\n\n")
        .trim()

    private fun decodeEntities(raw: String): String {
        var decoded = raw
        repeat(MAX_ENTITY_DECODE_PASSES) {
            val before = decoded
            decoded = numericEntity.replace(decoded) { match ->
                val token = match.groupValues[1]
                val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                    token.drop(1).toIntOrNull(16)
                } else {
                    token.toIntOrNull()
                }
                codePoint
                    ?.takeIf(Character::isValidCodePoint)
                    ?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                    ?: match.value
            }
            decoded = namedEntity.replace(decoded) { match ->
                when (match.groupValues[1].lowercase()) {
                    "amp" -> "&"
                    "quot" -> "\""
                    "apos" -> "'"
                    "lt" -> "<"
                    "gt" -> ">"
                    "nbsp" -> " "
                    else -> match.value
                }
            }
            if (decoded == before) return decoded
        }
        return decoded
    }

    private fun referencesMatch(serviceReference: String, eventReference: String?): Boolean {
        val event = eventReference?.trim() ?: return false
        return normalizeReference(serviceReference) == normalizeReference(event)
    }

    private fun normalizeReference(value: String): String = value.trim().trimEnd(':')

    private const val MAX_ENTITY_DECODE_PASSES = 3
}
