package com.andreassamitsch.ilauncher.data.openwebif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWebifSignalMapperTest {
    @Test
    fun mapsActiveFrontendMeasurements() {
        val status = OpenWebifSignalMapper.fromDto(
            OpenWebifSignalResponseDto(
                tunertype = "DVB-S2",
                tunernumber = "A",
                snr = "78",
                snrDb = "13.42",
                agc = "64",
                ber = "0",
            ),
        )

        assertEquals("DVB-S2", status.tunerType)
        assertEquals("A", status.tunerNumber)
        assertEquals(78, status.snrPercent)
        assertEquals(13.42, status.snrDb!!, 0.001)
        assertEquals(64, status.agcPercent)
        assertEquals("0", status.ber)
        assertTrue(status.hasMeasurements)
    }

    @Test
    fun treatsIntegerSnrDbAsOpenWebifPercentageFallback() {
        val status = OpenWebifSignalMapper.fromDto(
            OpenWebifSignalResponseDto(
                snr = "71",
                snrDb = "71",
                agc = "55",
                ber = "0",
            ),
        )

        assertEquals(71, status.snrPercent)
        assertNull(status.snrDb)
    }

    @Test
    fun handlesEmptyFrontendResponse() {
        val status = OpenWebifSignalMapper.fromDto(
            OpenWebifSignalResponseDto(
                tunertype = "",
                tunernumber = "",
                snr = "",
                snrDb = "",
                agc = "",
                ber = "",
            ),
        )

        assertNull(status.tunerType)
        assertNull(status.tunerNumber)
        assertNull(status.snrPercent)
        assertNull(status.snrDb)
        assertNull(status.agcPercent)
        assertNull(status.ber)
        assertFalse(status.hasMeasurements)
    }

    @Test
    fun parsesLegacySignalXmlIncludingHistoricalAcgTag() {
        val status = OpenWebifLegacySignalParser.fromXml(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <e2frontendstatus>
                <e2snrdb>13.42 dB</e2snrdb>
                <e2snr>78 %</e2snr>
                <e2ber>0</e2ber>
                <e2acg>64 %</e2acg>
            </e2frontendstatus>
            """.trimIndent(),
        )

        assertEquals(78, status.snrPercent)
        assertEquals(13.42, status.snrDb!!, 0.001)
        assertEquals(64, status.agcPercent)
        assertEquals("0", status.ber)
        assertTrue(status.hasMeasurements)
    }

    @Test
    fun ignoresLegacyIntegerSnrDbPercentageMirror() {
        val status = OpenWebifLegacySignalParser.fromXml(
            """
            <e2frontendstatus>
                <e2snrdb>71 dB</e2snrdb>
                <e2snr>71 %</e2snr>
                <e2ber>0</e2ber>
                <e2agc>55 %</e2agc>
            </e2frontendstatus>
            """.trimIndent(),
        )

        assertEquals(71, status.snrPercent)
        assertNull(status.snrDb)
        assertEquals(55, status.agcPercent)
    }
}
