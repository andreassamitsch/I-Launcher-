package com.lagradost.cloudstream3

import org.junit.Assert.*
import org.junit.Test

class ILauncherEpisodeIdentityTest {
    @Test fun matchesNativeSeasonFormula() {
        assertEquals(200108, ILauncherEpisodeIdentity.nativeId(100, 2, 7))
        assertEquals(108, ILauncherEpisodeIdentity.nativeId(100, null, 7))
    }

    @Test fun preservesLegacyHash() {
        assertEquals("Provider|100|4|episode-data".hashCode(),
            ILauncherEpisodeIdentity.legacyId("Provider", 100, 4, "episode-data"))
    }

    @Test fun neverOverwritesNativeOrRepeatsMigration() {
        assertTrue(ILauncherEpisodeIdentity.mayCopy(false, true, false))
        assertFalse(ILauncherEpisodeIdentity.mayCopy(true, true, false))
        assertFalse(ILauncherEpisodeIdentity.mayCopy(false, true, true))
        assertFalse(ILauncherEpisodeIdentity.mayCopy(false, false, false))
    }
}
