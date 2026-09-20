package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServusShowRefreshSchedulePolicyTest {
    @Test fun onlySelectedAndDueShowsRefresh() {
        val now = 1_000_000L
        val last = mapOf("recent" to now - 60_000L, "due" to now - 900_000L)
        assertEquals(setOf("due", "new"),
            ServusShowRefreshSchedulePolicy.dueIds(linkedSetOf("recent", "due", "new"), now) { last[it] ?: 0L })
    }

    @Test fun clockRollbackDoesNotBlockRefresh() {
        assertEquals(setOf("show"), ServusShowRefreshSchedulePolicy.dueIds(setOf("show"), 500L) { 1_000L })
    }
}
