package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ServusPreviewBrandingTest {
    private val bundledLogoUri =
        "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.servus_news_90_logo}"

    @Test
    fun `maps current Servus 90-second transport logo to launcher resource`() {
        assertEquals(
            bundledLogoUri,
            PreviewChannelsMapper.localizePreviewLogoUri(
                packageName = "com.andreassamitsch.servusprovider",
                logoUri = "content://com.andreassamitsch.servusprovider.branding/servus_news_90_logo.png",
            ),
        )
    }

    @Test
    fun `maps legacy Servus 90-second resource uri to launcher resource`() {
        assertEquals(
            bundledLogoUri,
            PreviewChannelsMapper.localizePreviewLogoUri(
                packageName = "com.andreassamitsch.servusprovider",
                logoUri = "android.resource://com.andreassamitsch.servusprovider/drawable/servus_news_90_logo",
            ),
        )
    }

    @Test
    fun `does not rewrite unrelated provider logos`() {
        val original = "content://example.provider/logo.png"
        assertEquals(
            original,
            PreviewChannelsMapper.localizePreviewLogoUri(
                packageName = "example.provider",
                logoUri = original,
            ),
        )
    }
}
