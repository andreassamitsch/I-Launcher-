package com.lagradost.cloudstream3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ILauncherDirectPlayTest {
    @Test
    fun parsesMovieIdentity() {
        val request = ILauncherDirectPlay.parseRequest(
            "cloudstreamplay://v1?title=Dune%3A%20Part%20Two&type=movie&year=2024&imdbId=tt15239678",
        )
        requireNotNull(request)
        assertEquals("Dune: Part Two", request.title)
        assertEquals(ILauncherDirectPlay.MediaKind.Movie, request.kind)
        assertEquals(2024, request.year)
        assertEquals("tt15239678", request.imdbId)
        assertNull(request.season)
        assertNull(request.episode)
    }

    @Test
    fun parsesExactEpisodeIdentityAndUnicode() {
        val request = ILauncherDirectPlay.parseRequest(
            "cloudstreamplay://v1?title=Die%20drei%20%3F%3F%3F&type=episode&season=2&episode=4&originalTitle=The%20Three%20Investigators",
        )
        requireNotNull(request)
        assertEquals("Die drei ???", request.title)
        assertEquals("The Three Investigators", request.originalTitle)
        assertEquals(ILauncherDirectPlay.MediaKind.Episode, request.kind)
        assertEquals(2, request.season)
        assertEquals(4, request.episode)
    }

    @Test
    fun rejectsWrongVersionAndBlankTitle() {
        assertNull(ILauncherDirectPlay.parseRequest("cloudstreamplay://v2?title=Fallout&type=series"))
        assertNull(ILauncherDirectPlay.parseRequest("cloudstreamplay://v1?title=%20%20&type=series"))
    }

    @Test
    fun titleNormalizationIsStrictButPunctuationAgnostic() {
        assertEquals("dune part two", ILauncherDirectPlay.normalizeTitle("  Dune:   Part Two "))
        assertEquals("die drei", ILauncherDirectPlay.normalizeTitle("Die drei ???"))
    }
}
