package com.andreassamitsch.ilauncher.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionPolicyTest {
    @Test
    fun newerRemoteVersionIsAccepted() {
        assertTrue(UpdateVersionPolicy.isNewer(remoteVersionCode = 12, localVersionCode = 11))
    }

    @Test
    fun sameOrOlderRemoteVersionIsRejected() {
        assertFalse(UpdateVersionPolicy.isNewer(remoteVersionCode = 11, localVersionCode = 11))
        assertFalse(UpdateVersionPolicy.isNewer(remoteVersionCode = 10, localVersionCode = 11))
    }
}
