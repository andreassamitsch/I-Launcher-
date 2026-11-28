package com.andreassamitsch.ilauncher.model

import java.util.Locale

enum class LiveTvArtworkSource {
    TmdbEpisode, TmdbFilm, TmdbSeries, TmdbFanart, Epg, TmdbPoster, SenderLogo, None,
}

data class LiveTvArtwork(val uri: String, val source: LiveTvArtworkSource)

/**
 * Select artwork independently of airing-specific text.
 * A confidently resolved widescreen backdrop is a format-quality preference for scripted
 * content; XMLTV currently provides no reliable pixel-quality measurement.
 */
fun LiveTvProgram.selectArtwork(forHero: Boolean = false): LiveTvArtwork? = artworkCandidates(forHero).firstOrNull()

fun LiveTvProgram.artworkCandidates(forHero: Boolean = false): List<LiveTvArtwork> {
    if (!hasValidAiring) return emptyList()
    fun candidate(uri: String?, source: LiveTvArtworkSource): LiveTvArtwork? =
        uri?.takeIf { it.isNotBlank() }?.let { LiveTvArtwork(it, source) }

    val epg = candidate(imageUri, LiveTvArtworkSource.Epg)
    if (tmdbId == null) return listOfNotNull(epg)
    val episode = candidate(episodeStillUri, LiveTvArtworkSource.TmdbEpisode)
    val backdrop = candidate(backdropUri, when (tmdbType) {
        MediaType.Movie -> LiveTvArtworkSource.TmdbFilm
        else -> LiveTvArtworkSource.TmdbSeries
    })
    val poster = if (forHero) null else candidate(posterUri, LiveTvArtworkSource.TmdbPoster)
    val factual = categories.orEmpty().any { category ->
        val value = category.lowercase(Locale.ROOT).trim()
        value in factualCategories ||
            value.startsWith("news ") || value.startsWith("nachrichten ") ||
            value.startsWith("sport ") || value.startsWith("sports ") ||
            value.endsWith(" magazine") || value.endsWith(" magazin")
    }
    // News, sport and factual formats often have an airing-specific photo that a generic
    // series image cannot represent. Do not discard that source for a generic backdrop.
    val fanart = candidate(alternateBackdropUri, LiveTvArtworkSource.TmdbFanart)
    val scripted = tmdbType == MediaType.Movie || tmdbType == MediaType.Series || tmdbType == MediaType.Episode
    val candidates = when {
        factual || !scripted -> listOfNotNull(epg, backdrop, episode, fanart, poster)
        forHero && tmdbType != MediaType.Movie -> listOfNotNull(episode, fanart, backdrop, epg)
        forHero -> listOfNotNull(backdrop, fanart, epg)
        else -> listOfNotNull(backdrop, episode, fanart, epg, poster)
    }
    return candidates.distinctBy { it.uri }
}

private val factualCategories = setOf(
    "news", "nachrichten", "nachrichtenmagazin", "magazine", "magazin",
    "sport", "sports", "sportmagazin", "sportnachrichten", "fußball", "fussball",
    "football", "soccer", "tennis", "weather", "wetter", "current affairs",
    "reportage", "documentary", "dokumentation", "talk show", "talkshow",
    "reality", "reality tv", "shopping", "teleshopping", "game show", "quiz",
)
