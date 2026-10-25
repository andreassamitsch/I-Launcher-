package com.andreassamitsch.ilauncher.ui.livetv

import androidx.media3.common.C
import com.andreassamitsch.ilauncher.data.livetv.LiveTvAudioLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvAudioSelectionTest {
    private fun track(
        index: Int,
        language: String? = "deu",
        label: String? = null,
        roles: Int = 0,
        flags: Int = 0,
        supported: Boolean = true,
    ) = LiveTvAudioTrack(0, index, language, label, roles, flags, 2, supported, false)

    @Test fun germanMainBeatsDefaultHearingOrDescriptiveAudio() {
        val special = track(0, label = "Deutsch (Hörgeschädigte)", flags = C.SELECTION_FLAG_DEFAULT)
        val normal = track(1, language = "de-DE", label = "Deutsch")
        assertEquals(
            normal,
            LiveTvAudioSelection.chooseAutomatic(
                listOf(special, normal), LiveTvAudioLanguage.German, true,
            ),
        )
        assertTrue(LiveTvAudioSelection.isSpecial(special))
        assertFalse(LiveTvAudioSelection.isSpecial(normal))
    }

    @Test fun audioDescriptionRoleFlagIsNotChosenByDefault() {
        val descriptive = track(0, roles = C.ROLE_FLAG_DESCRIBES_VIDEO)
        val main = track(1, roles = C.ROLE_FLAG_MAIN)
        assertEquals(
            main,
            LiveTvAudioSelection.chooseAutomatic(
                listOf(descriptive, main), LiveTvAudioLanguage.German, true,
            ),
        )
    }

    @Test fun languagePreferenceAndAutomaticModeAreRespected() {
        val german = track(0, "ger")
        val english = track(1, "eng")
        assertEquals(
            english,
            LiveTvAudioSelection.chooseAutomatic(
                listOf(german, english), LiveTvAudioLanguage.English, true,
            ),
        )
        assertNull(
            LiveTvAudioSelection.chooseAutomatic(
                listOf(german, english), LiveTvAudioLanguage.Automatic, false,
            ),
        )
    }

    @Test fun unsupportedTrackAndMissingNormalFallback() {
        val unsupported = track(0, supported = false)
        val special = track(1, label = "Audiodeskription")
        assertEquals(
            special,
            LiveTvAudioSelection.chooseAutomatic(
                listOf(unsupported, special), LiveTvAudioLanguage.German, true,
            ),
        )
        assertEquals(
            "Deutsch · Audiodeskription · Stereo",
            LiveTvAudioSelection.displayLabel(special, 2),
        )
    }

    @Test fun manualSelectionCanBeFoundAfterStreamReconnect() {
        val chosen = track(1, "deu", "Deutsch")
        assertEquals(chosen, LiveTvAudioSelection.findManual(listOf(track(0), chosen), chosen))
        assertNull(LiveTvAudioSelection.findManual(listOf(track(0)), chosen))
    }

    @Test fun unknownStoredLanguageFallsBackToGerman() {
        assertEquals(LiveTvAudioLanguage.German, LiveTvAudioLanguage.fromStored(null))
        assertEquals(LiveTvAudioLanguage.German, LiveTvAudioLanguage.fromStored("obsolete"))
        assertEquals(LiveTvAudioLanguage.Automatic, LiveTvAudioLanguage.fromStored("Automatic"))
    }
}
