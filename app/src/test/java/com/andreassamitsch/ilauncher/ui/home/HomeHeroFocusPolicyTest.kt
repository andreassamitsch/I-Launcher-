package com.andreassamitsch.ilauncher.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeroFocusPolicyTest {
    @Test fun metadataForDisplayedIdentityDoesNotWait() {
        assertEquals(0L, homeHeroFocusDelayMillis("live:channel1", "live:channel1"))
    }

    @Test fun distinctCardFocusSettlesBeforeHeroChanges() {
        assertEquals(180L, homeHeroFocusDelayMillis("watch:1", "watch:2"))
    }

    @Test fun returningToDisplayedCardCancelsPendingTransitionWithoutDelay() {
        assertEquals(0L, homeHeroFocusDelayMillis("watch:1", "watch:1"))
    }
}
