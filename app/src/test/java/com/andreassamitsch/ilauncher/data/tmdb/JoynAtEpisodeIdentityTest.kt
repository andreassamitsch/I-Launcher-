package com.andreassamitsch.ilauncher.data.tmdb

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynAtEpisodeIdentityTest {
    @Test fun searchResultRetainsCountryIdentity() {
        val german = Gson().fromJson(
            """{"id":101,"name":"Bauer sucht Frau","origin_country":["DE"]}""",
            TmdbSearchResultDto::class.java,
        )
        val austrian = Gson().fromJson(
            """{"id":102,"name":"Bauer sucht Frau","origin_country":["AT"]}""",
            TmdbSearchResultDto::class.java,
        )
        assertFalse("AT" in german.originCountry)
        assertTrue("AT" in austrian.originCountry)
    }

    @Test fun seriesDetailsRetainCountryIdentity() {
        val series = Gson().fromJson(
            """{"id":102,"name":"Bauer sucht Frau","origin_country":["AT"],"seasons":[{"season_number":23}]}""",
            TmdbMediaDetailsDto::class.java,
        )
        assertEquals(listOf("AT"), series.originCountry)
        assertEquals(23, series.seasons.single().seasonNumber)
    }
}
