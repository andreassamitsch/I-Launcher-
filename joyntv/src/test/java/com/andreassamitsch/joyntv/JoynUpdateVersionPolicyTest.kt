package com.andreassamitsch.joyntv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynUpdateVersionPolicyTest {
    @Test
    fun newerVersionIsAccepted() {
        assertTrue(JoynUpdateVersionPolicy.isNewer(remoteVersionCode = 11, localVersionCode = 10))
    }

    @Test
    fun sameOrOlderVersionIsRejected() {
        assertFalse(JoynUpdateVersionPolicy.isNewer(remoteVersionCode = 10, localVersionCode = 10))
        assertFalse(JoynUpdateVersionPolicy.isNewer(remoteVersionCode = 9, localVersionCode = 10))
    }
}
