package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ILauncherSourceLanguageTest {
    @Test fun recognizesExplicitLabels() {
        assertEquals(2, ILauncherSourceLanguage.rank("VOE Deutsch"))
        assertEquals(2, ILauncherSourceLanguage.rank("Stream (German)"))
        assertEquals(2, ILauncherSourceLanguage.rank("🇩🇪 Stream"))
        assertEquals(0, ILauncherSourceLanguage.rank("Stream Englisch"))
        assertEquals(0, ILauncherSourceLanguage.rank("Stream [EN]"))
        assertEquals(1, ILauncherSourceLanguage.rank("Stream 1080p"))
        assertEquals(1, ILauncherSourceLanguage.rank("Deutsch / English"))
    }

    @Test fun strictAndEarlySelection() {
        assertEquals(true, ILauncherSourceLanguage.allowed("only", 2))
        assertEquals(false, ILauncherSourceLanguage.allowed("only", 1))
        assertEquals(false, ILauncherSourceLanguage.allowed("only", 0))
        assertEquals(true, ILauncherSourceLanguage.allowed("prefer", 0))
        assertEquals(false, ILauncherSourceLanguage.earlyAllowed("prefer", 0))
        assertEquals(true, ILauncherSourceLanguage.earlyAllowed("prefer", 2))
        assertEquals(true, ILauncherSourceLanguage.earlyAllowed("all", 0))
    }
}
