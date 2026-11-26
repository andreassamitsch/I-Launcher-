package com.andreassamitsch.joyntv

internal object JoynSeriesWatchNextPolicy {
    private fun String?.key() = this?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(java.util.Locale.ROOT)

    // Never collapse distinct provider series (e.g. Austrian and German shows with the same title).
    fun sameSeries(a: JoynMediaItem, b: JoynMediaItem): Boolean {
        if (a.type != JoynMediaType.EPISODE || b.type != JoynMediaType.EPISODE) return false
        val ai = a.seriesId.key()
        val bi = b.seriesId.key()
        if (ai != null && bi != null) return ai == bi
        val ap = a.seriesPath.key()
        val bp = b.seriesPath.key()
        if (ap != null && bp != null) return ap == bp
        val asid = a.seasonId.key()
        val bsid = b.seasonId.key()
        if (asid != null && bsid != null && asid == bsid) return true
        // Legacy records without authoritative identifiers can only be matched to other legacy records.
        return ai == null && bi == null && ap == null && bp == null &&
            a.seriesTitle.key() != null && a.seriesTitle.key() == b.seriesTitle.key()
    }

    fun completedMedia(state: JoynCompletedSeries) = JoynMediaItem(
        id = state.seriesKey, title = state.seriesTitle, type = JoynMediaType.EPISODE,
        seriesId = state.seriesId, seriesPath = state.seriesPath, seriesTitle = state.seriesTitle,
        seasonId = state.seasonId, seasonNumber = state.seasonNumber, episodeNumber = state.episodeNumber,
    )

    // Null means the catalogue did not supply enough coordinates for a safe comparison.
    fun compareEpisodes(a: JoynMediaItem, b: JoynMediaItem): Int? {
        val sa = a.seasonNumber
        val sb = b.seasonNumber
        if (sa != null && sb != null && sa != sb) return sa.compareTo(sb)
        if ((sa == null || sb == null) &&
            (a.seasonId.key() == null || a.seasonId.key() != b.seasonId.key())) return null
        val ea = a.episodeNumber ?: return null
        val eb = b.episodeNumber ?: return null
        return ea.compareTo(eb)
    }

    fun eligible(entries: List<JoynContinueWatchingEntry>): List<JoynContinueWatchingEntry> {
        val result = mutableListOf<JoynContinueWatchingEntry>()
        for (entry in entries.sortedByDescending { it.updatedAt }) {
            if (!JoynContinueWatchingPolicy.shouldContinue(entry.media, entry.positionMs, entry.durationMs)) continue
            val index = result.indexOfFirst { sameSeries(it.media, entry.media) }
            if (index < 0) result.add(entry)
            else if (compareEpisodes(entry.media, result[index].media)?.let { it > 0 } == true) {
                result[index] = entry
            }
        }
        return result.sortedByDescending { it.updatedAt }
    }

    fun resumeWins(resume: JoynMediaItem, next: JoynMediaItem): Boolean =
        sameSeries(resume, next)
}
