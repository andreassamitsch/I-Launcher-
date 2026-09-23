package com.andreassamitsch.ilauncher.ui.components

import android.media.tv.TvContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNextStatusBadgeTest {
    @Test fun distinguishesNextFromNew() {
        assertEquals("Nächste Folge", watchNextBadgeLabel(TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT))
        assertEquals("Neue Folge", watchNextBadgeLabel(TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW))
    }

    @Test fun continueAndUnknownKeepProgressInsteadOfNewBadge() {
        assertNull(watchNextBadgeLabel(TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE))
        assertNull(watchNextBadgeLabel(null))
    }
}
