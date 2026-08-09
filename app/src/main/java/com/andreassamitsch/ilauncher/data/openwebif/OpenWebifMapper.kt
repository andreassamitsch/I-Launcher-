package com.andreassamitsch.ilauncher.data.openwebif

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram

internal object OpenWebifMapper {
    fun bouquets(services: List<OpenWebifServiceDto>): List<OpenWebifBouquet> =
        services.mapNotNull { service ->
            val name = service.serviceName?.trim()?.takeIf { it.isNotBlank() }
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
            val reference = service.serviceReference?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = service.serviceName?.trim()?.takeIf { it.isNotBlank() }
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
        val programTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return LiveTvProgram(
            eventId = id,
            title = programTitle,
            shortDescription = shortdesc?.trim()?.takeIf { it.isNotBlank() },
            longDescription = longdesc?.trim()?.takeIf { it.isNotBlank() },
            startUtcMillis = start * 1_000L,
            durationMillis = duration.coerceAtLeast(0L) * 1_000L,
        )
    }

    private fun referencesMatch(serviceReference: String, eventReference: String?): Boolean {
        val event = eventReference?.trim() ?: return false
        return normalizeReference(serviceReference) == normalizeReference(event)
    }

    private fun normalizeReference(value: String): String = value.trim().trimEnd(':')
}
