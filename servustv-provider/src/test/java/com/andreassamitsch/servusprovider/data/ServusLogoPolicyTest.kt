package com.andreassamitsch.servusprovider.data

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
            resources = listOf("rbtv_display_art_landscape", "rbtv_wordmark"),
        )

        assertNotNull(uri)
        assertTrue(uri!!.contains("SHOW-WORDMARK/rbtv_wordmark"))
        assertFalse(uri.contains("c_fill"))
    }

    @Test
    fun plainLogoResourceIsAcceptedAsFallback() {
        val uri = ServusCatalogPolicy.titleTreatment(
            id = "SHOW-LOGO",
            resources = listOf("rbtv_display_art_landscape", "rbtv_logo"),
        )

        assertNotNull(uri)
        assertTrue(uri!!.contains("SHOW-LOGO/rbtv_logo"))
    }

    @Test
    fun artworkLikeLogoResourceIsNotMistakenForTransparentWordmark() {
        val uri = ServusCatalogPolicy.titleTreatment(
            id = "SHOW-ART",
            resources = listOf("rbtv_logo_landscape", "rbtv_display_art_landscape"),
        )

        assertNull(uri)
    }
}
