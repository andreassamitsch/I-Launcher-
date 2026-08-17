package com.andreassamitsch.ilauncher.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigationRequestTest {
    @Test
    fun explicitLauncherHomeActionIsRecognized() {
        assertTrue(isLauncherHomeRequest(ACTION_RETURN_TO_LAUNCHER_HOME, emptySet()))
    }

    @Test
    fun androidMainHomeIntentIsRecognized() {
        assertTrue(
            isLauncherHomeRequest(
                action = "android.intent.action.MAIN",
                categories = setOf("android.intent.category.HOME", "android.intent.category.DEFAULT"),
            ),
        )
    }

    @Test
    fun ordinaryLauncherIntentDoesNotForceHomeReset() {
        assertFalse(
            isLauncherHomeRequest(
                action = "android.intent.action.MAIN",
                categories = setOf("android.intent.category.LAUNCHER"),
            ),
        )
    }
}
