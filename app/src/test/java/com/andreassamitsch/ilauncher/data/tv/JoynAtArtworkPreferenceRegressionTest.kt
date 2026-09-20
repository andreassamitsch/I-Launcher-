package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.data.home.WatchNextArtworkMode
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.WatchNextItem
import com.andreassamitsch.ilauncher.ui.home.watchNextCardArtwork
import com.andreassamitsch.ilauncher.ui.home.watchNextHeroArtwork
import org.junit.Assert.assertEquals
import org.junit.Test

class JoynAtArtworkPreferenceRegressionTest {
    @Test fun heroAndCardRespectDistinctJoynEpisodeAndSeriesAssets() {
        val row = WatchNextItem(
            id = 27L, sourceOrder = 0, packageName = "com.andreassamitsch.joyntv",
            programType = android.media.tv.TvContract.PreviewPrograms.TYPE_TV_EPISODE,
            title = "Bauer sucht Frau", releaseDate = null,
            seasonDisplayNumber = "23", episodeDisplayNumber = "3",
            episodeTitle = "Die Qual der Wahl zum Hofwochenstart",
            shortDescription = "Beschreibung der österreichischen Folge",
            posterArtUri = "https://at.example/episode.jpg",
            thumbnailUri = "https://at.example/season.jpg",
            logoUri = "https://at.example/logo.png", intentUri = "intent://at-video",
            durationMillis = 5_700_000L, playbackPositionMillis = 180_000L,
            watchNextType = null, lastEngagementTimeUtcMillis = null,
        )
        val media = WatchNextMediaMapper.base(row)
        assertEquals(MediaType.Episode, media.type)
        assertEquals("https://at.example/logo.png", media.logoUri)
        assertEquals("Beschreibung der österreichischen Folge", media.overview)
        assertEquals("https://at.example/episode.jpg", watchNextCardArtwork(media, WatchNextArtworkMode.Episode))
        assertEquals("https://at.example/season.jpg", watchNextHeroArtwork(media, WatchNextArtworkMode.Series).first)
    }
}
