package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgChannelMatcherTest {
    @Test
    fun `service reference match wins over sender name`() {
        val serviceRef = "1:0:19:283D:3FB:1:C00000:0:0:0:"
        val source = listOf(
            EpgSourceChannel(
                xmltvChannelId = serviceRef,
                displayName = "Unwichtiger Name",
                serviceReferenceHints = listOf(M3uEpgParser.normalizeServiceReference(serviceRef)),
            ),
        )

        val mapping = EpgChannelMatcher.autoMappings(
            channels = listOf(LiveTvChannel(serviceReference = serviceRef, name = "Das Erste HD")),
            sourceChannels = source,
            manualMappings = emptyList(),
        ).single()

        assertEquals(serviceRef, mapping.xmltvChannelId)
        assertEquals(EpgChannelMatcher.METHOD_SERVICE_REFERENCE, mapping.matchMethod)
    }

    @Test
    fun `normalizes common hd and prosieben naming`() {
        val mapping = EpgChannelMatcher.autoMappings(
            channels = listOf(
                LiveTvChannel(
                    serviceReference = "1:0:1:111:222:1:C00000:0:0:0:",
                    name = "ProSieben HD",
                ),
            ),
            sourceChannels = listOf(
                EpgSourceChannel(
                    xmltvChannelId = "Pro7.de",
                    tvgName = "ProSieben",
                    displayName = "ProSieben",
                ),
            ),
            manualMappings = emptyList(),
        ).single()

        assertEquals("Pro7.de", mapping.xmltvChannelId)
        assertEquals(EpgChannelMatcher.METHOD_EXACT_NAME, mapping.matchMethod)
    }
}
