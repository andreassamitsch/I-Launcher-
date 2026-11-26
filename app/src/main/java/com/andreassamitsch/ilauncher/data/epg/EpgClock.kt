package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram

/** Prefer the receiver's valid airing and keep its merged metadata when the XMLTV clock differs. */
internal fun advanceEpgChannel(
    receiver: LiveTvChannel,
    previous: LiveTvChannel?,
    guide: List<LiveTvProgram>,
    now: Long,
): LiveTvChannel {
    fun LiveTvProgram.active(): Boolean = startUtcMillis <= now && now < endUtcMillis
    fun sameAiring(a: LiveTvProgram, b: LiveTvProgram): Boolean =
        a.startUtcMillis == b.startUtcMillis &&
            EpgChannelMatcher.normalizeName(a.title) == EpgChannelMatcher.normalizeName(b.title) &&
            (a.eventId == null || b.eventId == null || a.eventId == b.eventId) &&
            (a.seasonNumber == null || b.seasonNumber == null || a.seasonNumber == b.seasonNumber) &&
            (a.episodeNumber == null || b.episodeNumber == null || a.episodeNumber == b.episodeNumber)
    fun withPrevious(base: LiveTvProgram): LiveTvProgram {
        val old = listOfNotNull(previous?.now, previous?.next).firstOrNull { sameAiring(base, it) }
            ?: return base
        return old.copy(
            eventId = base.eventId ?: old.eventId,
            startUtcMillis = base.startUtcMillis,
            durationMillis = base.durationMillis,
            title = base.title,
            shortDescription = base.shortDescription?.takeIf { it.isNotBlank() } ?: old.shortDescription,
            longDescription = base.longDescription?.takeIf { it.isNotBlank() } ?: old.longDescription,
            subtitle = base.subtitle?.takeIf { it.isNotBlank() } ?: old.subtitle,
            categories = base.categories?.takeIf { it.isNotEmpty() } ?: old.categories,
            seasonNumber = base.seasonNumber ?: old.seasonNumber,
            episodeNumber = base.episodeNumber ?: old.episodeNumber,
            releaseYear = base.releaseYear ?: old.releaseYear,
            imageUri = base.imageUri ?: old.imageUri,
        )
    }

    val receiverNow = listOfNotNull(receiver.now, receiver.next).firstOrNull { it.active() }
    val guideNow = guide.firstOrNull { it.active() }
    val oldNow = listOfNotNull(previous?.now, previous?.next).firstOrNull { it.active() }
    val current = receiverNow?.let(::withPrevious)
        ?: guideNow?.let(::withPrevious)
        ?: oldNow
    val boundary = current?.endUtcMillis ?: now
    val next = receiver.next?.takeIf { it.startUtcMillis >= boundary }?.let(::withPrevious)
        ?: guide.firstOrNull { it.startUtcMillis >= boundary }?.let(::withPrevious)
        ?: previous?.next?.takeIf { it.startUtcMillis >= boundary }
    return receiver.copy(now = current, next = next)
}
