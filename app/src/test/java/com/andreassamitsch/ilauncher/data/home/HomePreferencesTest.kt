package com.andreassamitsch.ilauncher.data.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePreferencesTest {
    @Test
    fun `merge keeps saved order and appends new rows`() {
        val result = HomePreferences.mergeOrder(
            saved = listOf("apps", "watch_next", "missing"),
            available = listOf("watch_next", "live_tv", "apps", "preview:1"),
        )
        assertEquals(listOf("apps", "watch_next", "live_tv", "preview:1"), result)
    }

    @Test
    fun `move shifts row without losing items`() {
        val order = listOf("watch_next", "live_tv", "apps")
        assertEquals(
            listOf("watch_next", "apps", "live_tv"),
            HomePreferences.move(order, "apps", -1),
        )
    }
}
