package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Test

class JoynProtocolTest {
    @Test
    fun playbackSignature_matchesReferenceVector() {
        assertEquals(
            "6ddc16493d09ab1c8c1d952822eb866700c14470",
            JoynProtocol.playbackSignature("token"),
        )
    }

    @Test
    fun countryTenants_keepAuthAndGraphQlRulesSeparate() {
        assertEquals("JOYN_DE", JoynCountry.DE.authTenant)
        assertEquals("JOYN", JoynCountry.DE.graphqlTenant)
        assertEquals("JOYN_AT", JoynCountry.AT.graphqlTenant)
        assertEquals("JOYN_CH", JoynCountry.CH.graphqlTenant)
    }
}
