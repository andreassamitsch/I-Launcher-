package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynExactEpisodeMetadataTest {
    private val remote = JoynContinueWatchingEntry(
        assetId = "asset-at", media = JoynMediaItem(
            id = "asset-at", videoId = "video-at", title = "Bauer sucht Frau",
            seriesTitle = "Bauer sucht Frau", type = JoynMediaType.EPISODE,
            seasonId = "season-at-23", seasonNumber = 23, episodeNumber = 3,
        ), positionMs = 180_000L, durationMs = 5_700_000L,
    )

    @Test fun exactAustrianEpisodeProvidesEpisodeAndSeasonArtwork() {
        val episode = remote.media.copy(title = "F3: Die Qual der Wahl zum Hofwochenstart",
            description = "Kurzbeschreibung der österreichischen Folge",
            imageUrl = "https://at.example/episode-still.jpg")
        val series = JoynMediaItem(id = "series-at", title = "Bauer sucht Frau",
            type = JoynMediaType.SERIES, logoUrl = "https://at.example/logo.png",
            backdropUrl = "https://at.example/series-hero.jpg")
        val enriched = JoynExactEpisodeMetadata.enrich(remote, episode, series,
            "https://at.example/season23.jpg").media
        assertEquals("https://at.example/episode-still.jpg", enriched.imageUrl)
        assertEquals("https://at.example/season23.jpg", enriched.seasonArtworkUrl)
        assertEquals("https://at.example/logo.png", enriched.logoUrl)
        assertEquals("Kurzbeschreibung der österreichischen Folge", enriched.description)
        assertEquals(23, enriched.seasonNumber)
        assertEquals(3, enriched.episodeNumber)
    }

    @Test fun similarlyTitledGermanEpisodeCannotSupplyArtwork() {
        val german = remote.media.copy(id = "asset-de", videoId = "video-de",
            imageUrl = "https://de.example/episode.jpg")
        val unchanged = JoynExactEpisodeMetadata.enrich(remote, german, null, null).media
        assertNull(unchanged.imageUrl)
    }
}
