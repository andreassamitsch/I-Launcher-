package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.WatchNextItem
import com.andreassamitsch.ilauncher.data.home.WatchNextArtworkMode
import com.andreassamitsch.ilauncher.ui.home.watchNextHeroArtwork
import com.andreassamitsch.ilauncher.ui.home.watchNextCardArtwork
import org.junit.Assert.assertEquals
import org.junit.Test

class JoynWatchNextSourceMetadataTest {
    @Test fun austriaSeriesLogoSeasonHeroEpisodeStillAndSynopsisRemainSeparate() {
        val row = WatchNextItem(
            id = 123L, sourceOrder = 0, packageName = "com.andreassamitsch.joyntv",
            programType = android.media.tv.TvContract.PreviewPrograms.TYPE_TV_EPISODE,
            title = "Bauer sucht Frau", releaseDate = null, seasonDisplayNumber = "23",
            episodeDisplayNumber = "3", episodeTitle = "F3: Die Qual der Wahl",
            shortDescription = "Die österreichische Episodenbeschreibung",
            posterArtUri = "https://at.example/episode.jpg",
            thumbnailUri = "https://at.example/season23.jpg",
            logoUri = "https://at.example/logo.png", intentUri = "intent://at",
            durationMillis = null, playbackPositionMillis = null, watchNextType = null,
            lastEngagementTimeUtcMillis = null,
        )
        val media = WatchNextMediaMapper.base(row)
        assertEquals(MediaType.Episode, media.type)
        assertEquals("https://at.example/logo.png", media.logoUri)
        assertEquals("Die österreichische Episodenbeschreibung", media.overview)
        assertEquals("https://at.example/episode.jpg", watchNextCardArtwork(media, WatchNextArtworkMode.Episode))
        assertEquals("https://at.example/season23.jpg", watchNextHeroArtwork(media, WatchNextArtworkMode.Series).first)
    }
}
