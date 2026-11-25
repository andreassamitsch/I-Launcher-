package com.lagradost.cloudstream3

import org.junit.Assert.assertEquals
import org.junit.Test
import com.lagradost.cloudstream3.ILauncherProviderStartup.Decision

class ILauncherProviderStartupTest {
    @Test fun coldStartDoesNotConfuseFirstProviderWithPinnedProvider() {
        assertEquals(Decision.Wait, ILauncherProviderStartup.decide("Serienstream", setOf("Moflix"), false, 25))
        assertEquals(Decision.Ready, ILauncherProviderStartup.decide("Serienstream", setOf("Moflix", "Serienstream"), false, 25))
    }

    @Test fun unpinnedWaitsForInitialLoadToPreserveProviderPriority() {
        assertEquals(Decision.Wait, ILauncherProviderStartup.decide(null, setOf("Moflix"), false, 25))
        assertEquals(Decision.Ready, ILauncherProviderStartup.decide(null, setOf("Moflix", "Serienstream"), true, 25))
    }

    @Test fun emptyDuringStartupIsNotMissingInstallation() {
        assertEquals(Decision.Wait, ILauncherProviderStartup.decide(null, emptySet(), false, 0))
        assertEquals(Decision.Unavailable, ILauncherProviderStartup.decide(null, emptySet(), false, 0, deadlineReached = true))
        assertEquals(Decision.NoExtensions, ILauncherProviderStartup.decide(null, emptySet(), true, 0))
    }

    @Test fun installedButUnavailableIsNotSetupError() {
        assertEquals(Decision.Unavailable, ILauncherProviderStartup.decide("Serienstream", setOf("Moflix"), true, 25))
        assertEquals(Decision.Unavailable, ILauncherProviderStartup.decide(null, emptySet(), true, 25))
        assertEquals(Decision.Unavailable, ILauncherProviderStartup.decide(null, emptySet(), false, 25, safeMode = true))
    }

    @Test fun timeoutAllowsUnpinnedAvailableProvidersButNeverSwitchesPinned() {
        assertEquals(Decision.Ready, ILauncherProviderStartup.decide(null, setOf("Moflix"), false, 25, deadlineReached = true))
        assertEquals(Decision.Unavailable, ILauncherProviderStartup.decide("Serienstream", setOf("Moflix"), false, 25, deadlineReached = true))
    }
}
