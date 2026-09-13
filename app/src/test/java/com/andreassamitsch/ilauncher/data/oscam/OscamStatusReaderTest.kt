package com.andreassamitsch.ilauncher.data.oscam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OscamStatusReaderTest {
    @Test
    fun extractsServiceIdFromEnigma2ServiceReference() {
        assertEquals(
            0x132F,
            OscamStatusReader.serviceIdFromReference("1:0:19:132F:3EF:1:C00000:0:0:0:"),
        )
    }

    @Test
    fun invalidServiceReferenceHasNoServiceId() {
        assertNull(OscamStatusReader.serviceIdFromReference("not-a-service-reference"))
    }

    @Test
    fun recognisesOscamFailureAnswers() {
        assertTrue(OscamStatusReader.isFailureAnswer("timeout"))
        assertTrue(OscamStatusReader.isFailureAnswer("NOT FOUND"))
        assertTrue(OscamStatusReader.isFailureAnswer("no card"))
        assertFalse(OscamStatusReader.isFailureAnswer("localreader"))
        assertFalse(OscamStatusReader.isFailureAnswer(null))
    }
}
