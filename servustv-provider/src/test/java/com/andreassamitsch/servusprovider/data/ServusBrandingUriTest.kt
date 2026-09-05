package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusBrandingUriTest {
    @Test
    fun `recognizes current and legacy bundled 90-second logo markers`() {
        assertTrue(ServusBranding.isNinetySecondLogoUri(ServusBranding.NEWS_90_SECONDS_LOGO_URI))
        assertTrue(ServusBranding.isNinetySecondLogoUri(ServusBranding.NEWS_90_SECONDS_LEGACY_RESOURCE_URI))
        assertFalse(ServusBranding.isNinetySecondLogoUri(ServusBranding.NEWS_LOGO_URI))
    }
}
