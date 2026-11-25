package com.andreassamitsch.ilauncher.model

import java.util.Locale

enum class LiveTvArtworkSource {
    TmdbEpisode, TmdbFilm, TmdbSeries, Epg, TmdbPoster, SenderLogo, None,
}

data class LiveTvArtwork(val uri: String, val source: LiveTvArtworkSource)

/**
 * Select artwork independently of airing-specific text.
 * A confidently resolved widescreen backdrop is a format-quality preference for scripted
 * content; XMLTV currently provides no reliable pixel-quality measurement.
 */
fun LiveTvProgram.selectArtwork(forHero: Boolean = false): LiveTvArtwork? {
    fun candidate(uri: String?, source: LiveTvArtworkSource): LiveTvArtwork? =
        uri?.takeIf { it.isNotBlank() }?.let { LiveTvArtwork(it, source) }

    val epg = candidate(imageUri, LiveTvArtworkSource.Epg)
    if (tmdbId == null) return epg
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
    if (factual && epg != null) return epg
    if (episode != null) return episode
    if (tmdbType == MediaType.Movie || tmdbType == MediaType.Series || tmdbType == MediaType.Episode) {
        return backdrop ?: epg ?: poster
    }
    return epg ?: backdrop ?: poster
}

private val factualCategories = setOf(
    "news", "nachrichten", "nachrichtenmagazin", "magazine", "magazin",
    "sport", "sports", "sportmagazin", "sportnachrichten", "fußball", "fussball",
    "football", "soccer", "tennis", "weather", "wetter", "current affairs",
    "reportage", "documentary", "dokumentation", "talk show", "talkshow",
    "reality", "reality tv", "shopping", "teleshopping", "game show", "quiz",
)
