package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import java.text.Normalizer

object MediaTitleParser {
    private val seasonEpisodeRegex = Regex(
        pattern = "(?i)(?:^|[\\s._:-])S(\\d{1,2})[\\s._:-]*E(\\d{1,3})(?:$|[\\s._:-])",
    )
    private val alternateEpisodeRegex = Regex(
        pattern = "(?i)(?:^|[\\s._-])(\\d{1,2})x(\\d{1,3})(?:$|[\\s._-])",
    )
    private val yearRegex = Regex("(?:^|[\\s(\\[])((?:19|20)\\d{2})(?:[)\\]]|$)")

    fun parse(lookup: MediaLookup): ParsedMediaLookup {
        var title = lookup.rawTitle.trim()
        var seasonNumber = lookup.seasonNumber
        var episodeNumber = lookup.episodeNumber

        val seasonEpisodeMatch = seasonEpisodeRegex.find(title)
            ?: alternateEpisodeRegex.find(title)
        if (seasonEpisodeMatch != null) {
            seasonNumber = seasonNumber ?: seasonEpisodeMatch.groupValues[1].toIntOrNull()
            episodeNumber = episodeNumber ?: seasonEpisodeMatch.groupValues[2].toIntOrNull()
            title = title.removeRange(seasonEpisodeMatch.range).trim(' ', '-', '_', '.', '·')
        }

        val yearMatch = yearRegex.find(title)
        val releaseYear = lookup.releaseYear ?: yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (yearMatch != null) {
            title = title.removeRange(yearMatch.range).trim(' ', '-', '_', '.', '(', ')', '[', ']', '·')
        }

        val inferredType = when {
            seasonNumber != null || episodeNumber != null -> MediaType.Episode
            else -> lookup.typeHint
        }

        val safeTitle = title.ifBlank { lookup.rawTitle.trim() }
        return ParsedMediaLookup(
            title = safeTitle,
            normalizedTitle = normalizeTitle(safeTitle),
            typeHint = inferredType,
            releaseYear = releaseYear,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
    }

    fun normalizeTitle(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace('&', ' ')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
