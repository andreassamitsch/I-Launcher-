package com.andreassamitsch.servusprovider.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusMediaResourcesTest {
    @Test
    fun objectResourcesKeepApiUrlAndOrientation() {
        val card = Gson().fromJson(
            """
            {
              "id":"SHOW",
              "media_resources": {
                "rbtv_title_treatment_landscape": {
                  "url":"https://resources.redbull.tv/SHOW/rbtv_title_treatment_landscape/{im}?namespace=stv",
                  "orientation":"landscape"
                },
                "rbtv_display_art_square": {
                  "url":"https://resources.redbull.tv/SHOW/rbtv_display_art_square/{im}?namespace=stv",
                  "orientation":"square"
                }
              }
            }
            """.trimIndent(),
            ServusCardDto::class.java,
        )

        val treatment = card.mediaResources.getValue("rbtv_title_treatment_landscape")
        assertEquals(
            "https://resources.redbull.tv/SHOW/rbtv_title_treatment_landscape/{im}?namespace=stv",
            treatment.url,
        )
        assertEquals("landscape", treatment.orientation)
        assertEquals("square", card.mediaResources.getValue("rbtv_display_art_square").orientation)
    }

    @Test
    fun directUrlStringIsPreserved() {
        val card = Gson().fromJson(
            """
            {
              "media_resources": {
                "rbtv_wordmark":"https://cdn.example/wordmark.webp"
              }
            }
            """.trimIndent(),
            ServusCardDto::class.java,
        )

        assertEquals("https://cdn.example/wordmark.webp", card.mediaResources.getValue("rbtv_wordmark").url)
    }

    @Test
    fun legacyResourceNameArrayRemainsAvailableWithoutInventedMetadata() {
        val card = Gson().fromJson(
            """{"media_resources":["rbtv_title_treatment","rbtv_display_art_landscape"]}""",
            ServusCardDto::class.java,
        )

        assertTrue(card.mediaResources.keys.containsAll(listOf("rbtv_title_treatment", "rbtv_display_art_landscape")))
        assertNull(card.mediaResources.getValue("rbtv_title_treatment").url)
        assertNull(card.mediaResources.getValue("rbtv_title_treatment").orientation)
    }
}
