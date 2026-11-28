package com.andreassamitsch.servusprovider.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusUpdatePolicyTest {
    @Test
    fun onlyHigherVersionCodeIsAnUpdate() {
        assertTrue(ServusUpdateVersionPolicy.isNewer(remoteVersionCode = 12, localVersionCode = 11))
        assertFalse(ServusUpdateVersionPolicy.isNewer(remoteVersionCode = 11, localVersionCode = 11))
        assertFalse(ServusUpdateVersionPolicy.isNewer(remoteVersionCode = 10, localVersionCode = 11))
    }

    @Test
    fun previouslyInstalledDownloadIsNeverRestored() {
        val cached = ServusUpdateInfo(71, "dev.71", "https://example.com/update.apk", "hash")
        assertFalse(ServusUpdateVersionPolicy.shouldRestoreDownload(cached, 71))
        assertFalse(ServusUpdateVersionPolicy.shouldRestoreDownload(cached, 72))
        assertTrue(ServusUpdateVersionPolicy.shouldRestoreDownload(cached, 70))
    }

    @Test
    fun popupRejectsInstalledAndOlderVersionsInEveryDownloadState() {
        val cached = ServusUpdateInfo(71, "dev.71", "https://example.com/update.apk", "hash")
        val states = listOf(
            ServusUpdateState.Available(cached),
            ServusUpdateState.Downloading(cached, 1L, 50),
            ServusUpdateState.ReadyToInstall(cached, 1L),
        )
        for (state in states) {
            assertNull(ServusUpdatePopupPolicy.info(state, 71))
            assertNull(ServusUpdatePopupPolicy.info(state, 72))
            assertEquals(cached, ServusUpdatePopupPolicy.info(state, 70))
        }
    }

    @Test
    fun popupDeferralOnlyAppliesToTheDismissedVersion() {
        val policy = ServusUpdatePopupPolicy()
        assertTrue(policy.shouldShow(12))
        policy.dismiss(12)
        assertFalse(policy.shouldShow(12))
        assertTrue(policy.shouldShow(13))
        policy.reopen()
        assertTrue(policy.shouldShow(12))
    }

    @Test
    fun metadataUrlGetsCacheBuster() {
        val url = buildServusUpdateMetadataUrl(123456L)
        assertTrue(url.startsWith("https://raw.githubusercontent.com/andreassamitsch/I-Launcher-/servustv-downloads/update.json"))
        assertTrue(url.endsWith("?check=123456"))
    }
}
