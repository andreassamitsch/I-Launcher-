package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbTrailerSelectorTest {
    @Test
    fun `prefers official German trailer`() {
        val selected = TmdbTrailerSelector.preferredYouTubeId(
            listOf(
                video(key = "english", language = "en", official = true),
                video(key = "germanUnofficial", language = "de", official = false),
                video(key = "germanOfficial", language = "de", official = true),
            ),
        )

        assertEquals("germanOfficial", selected)
    }

    @Test
    fun `prefers German teaser over English trailer when German audio metadata exists`() {
        val selected = TmdbTrailerSelector.preferredYouTubeId(
            listOf(
                video(key = "germanTeaser", type = "Teaser", official = true, language = "de"),
                video(key = "englishTrailer", type = "Trailer", official = true, language = "en"),
            ),
        )

        assertEquals("germanTeaser", selected)
    }

    @Test
    fun `within same language trailer beats teaser`() {
        val selected = TmdbTrailerSelector.preferredYouTubeId(
            listOf(
                video(key = "teaser", type = "Teaser", official = true, language = "de"),
                video(key = "trailer", type = "Trailer", official = false, language = "de"),
            ),
        )

        assertEquals("trailer", selected)
    }

    @Test
    fun `ignores unsupported sites and video types`() {
        val selected = TmdbTrailerSelector.preferredYouTubeId(
            listOf(
                video(key = "vimeoTrailer", site = "Vimeo"),
                video(key = "featurette", type = "Featurette"),
                video(key = "youtubeTrailer"),
            ),
        )

        assertEquals("youtubeTrailer", selected)
    }

    @Test
    fun `returns null without usable YouTube trailer or teaser`() {
        val selected = TmdbTrailerSelector.preferredYouTubeId(
            listOf(
                video(key = "", type = "Trailer"),
                video(key = "clip", type = "Clip"),
                video(key = "vimeo", site = "Vimeo"),
            ),
        )

        assertNull(selected)
    }

    private fun video(
        key: String,
        site: String = "YouTube",
        type: String = "Trailer",
        official: Boolean = false,
        language: String? = null,
    ) = TmdbVideoDto(
        key = key,
        site = site,
        type = type,
        official = official,
        language = language,
    )
}
