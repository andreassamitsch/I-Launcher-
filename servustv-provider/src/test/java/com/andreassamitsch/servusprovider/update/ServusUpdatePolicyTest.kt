package com.andreassamitsch.servusprovider.update

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
    fun metadataUrlGetsCacheBuster() {
        val url = buildServusUpdateMetadataUrl(123456L)
        assertTrue(url.startsWith("https://raw.githubusercontent.com/andreassamitsch/I-Launcher-/servustv-downloads/update.json"))
        assertTrue(url.endsWith("?check=123456"))
    }
}
