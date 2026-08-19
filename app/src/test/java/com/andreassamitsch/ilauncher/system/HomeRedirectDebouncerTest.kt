package com.andreassamitsch.ilauncher.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRedirectDebouncerTest {
    @Test
    fun keyRedirectDoesNotSuppressImmediateDefaultHomeWindowFallback() {
        val debouncer = HomeRedirectDebouncer(debounceMillis = 750L)

        assertTrue(debouncer.shouldRedirect(HomeRedirectTrigger.KeyEvent, nowMillis = 1_000L))
        assertTrue(debouncer.shouldRedirect(HomeRedirectTrigger.DefaultHomeWindow, nowMillis = 1_050L))
    }

    @Test
    fun duplicateKeyRedirectsRemainDebounced() {
        val debouncer = HomeRedirectDebouncer(debounceMillis = 750L)

        assertTrue(debouncer.shouldRedirect(HomeRedirectTrigger.KeyEvent, nowMillis = 1_000L))
        assertFalse(debouncer.shouldRedirect(HomeRedirectTrigger.KeyEvent, nowMillis = 1_100L))
        assertTrue(debouncer.shouldRedirect(HomeRedirectTrigger.KeyEvent, nowMillis = 1_750L))
    }

    @Test
    fun duplicateWindowRedirectsRemainDebounced() {
        val debouncer = HomeRedirectDebouncer(debounceMillis = 750L)

        assertTrue(debouncer.shouldRedirect(HomeRedirectTrigger.DefaultHomeWindow, nowMillis = 2_000L))
        assertFalse(debouncer.shouldRedirect(HomeRedirectTrigger.DefaultHomeWindow, nowMillis = 2_100L))
        assertTrue(debouncer.shouldRedirect(HomeRedirectTrigger.DefaultHomeWindow, nowMillis = 2_750L))
    }
}
