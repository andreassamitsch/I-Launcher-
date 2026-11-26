package com.lagradost.cloudstream3

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class ILauncherPlaybackSessionTest {
    @Test fun forwardedLaunchReusesMainWithoutTaskReset() {
        val flags = ILauncherPlaybackSession.mainTaskFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP, flags)
    }

    @Test fun unrelatedIntentFlagsArePreserved() {
        val flags = ILauncherPlaybackSession.mainTaskFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun seriesChangesAreNotDerivedFromPreviousPlayback() {
        assertEquals("Percy Jackson S1:E3", ILauncherPlaybackSession.metadata("Percy Jackson", null, true, 1, 3).title)
        val echo = ILauncherPlaybackSession.metadata("Echo", "Chafa", true, 1, 1)
        assertEquals("Echo S1:E1", echo.title)
        assertEquals("Echo", echo.series)
        assertEquals("Chafa", echo.subtitle)
    }

    @Test fun preferSeriesOrMoviePosterOverEpisodeStill() {
        assertEquals("https://example.org/poster.jpg",
            ILauncherPlaybackSession.artworkUrl("https://example.org/poster.jpg", "https://example.org/episode.jpg"))
        assertEquals("https://example.org/episode.jpg",
            ILauncherPlaybackSession.artworkUrl(null, "https://example.org/episode.jpg"))
    }

    @Test fun rejectInvalidOrPrivateLocalArtworkAndDoNotReusePreviousCover() {
        assertEquals("https://example.org/episode.jpg",
            ILauncherPlaybackSession.artworkUrl("file:///data/private.jpg", "https://example.org/episode.jpg"))
        assertEquals(null, ILauncherPlaybackSession.artworkUrl("https://user:password@example.org/art.jpg", "not a URL"))
        assertEquals(null, ILauncherPlaybackSession.artworkUrl(null, null))
    }

    @Test fun movieAndMissingMetadataDoNotReuseSeries() {
        assertEquals(ILauncherPlaybackSession.Metadata("Film", null, null),
            ILauncherPlaybackSession.metadata("Film", "Film", false, 1, 1))
        assertEquals(ILauncherPlaybackSession.Metadata("", null, null),
            ILauncherPlaybackSession.metadata(null, null, false, null, null))
    }
}
