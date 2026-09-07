package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusMediaResourceDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusLogoPolicyTest {
    @Test
    fun wordmarkIsAcceptedAsShowLogoWithoutCropTransform() {
        val uri = ServusCatalogPolicy.titleTreatment(
            id = "SHOW-WORDMARK",
            resources = mapOf(
                "rbtv_display_art_landscape" to ServusMediaResourceDto(),
                "rbtv_wordmark" to ServusMediaResourceDto(),
            ),
        )

        assertNotNull(uri)
        assertTrue(uri!!.contains("SHOW-WORDMARK/rbtv_wordmark"))
        assertFalse(uri.contains("c_fill"))
    }

    @Test
    fun plainLogoResourceIsAcceptedAsFallback() {
        val uri = ServusCatalogPolicy.titleTreatment(
            id = "SHOW-LOGO",
            resources = mapOf(
                "rbtv_display_art_landscape" to ServusMediaResourceDto(),
                "rbtv_logo" to ServusMediaResourceDto(),
            ),
        )

        assertNotNull(uri)
        assertTrue(uri!!.contains("SHOW-LOGO/rbtv_logo"))
    }

    @Test
    fun artworkLikeLogoResourceIsNotMistakenForTransparentWordmark() {
        val uri = ServusCatalogPolicy.titleTreatment(
            id = "SHOW-ART",
            resources = mapOf(
                "rbtv_logo_landscape" to ServusMediaResourceDto(),
                "rbtv_display_art_landscape" to ServusMediaResourceDto(),
            ),
        )

        assertNull(uri)
    }
}
