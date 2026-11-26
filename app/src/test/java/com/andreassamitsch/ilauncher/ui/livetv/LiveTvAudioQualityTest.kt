package com.andreassamitsch.ilauncher.ui.livetv

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.andreassamitsch.ilauncher.data.livetv.LiveTvAudioLanguage
import com.andreassamitsch.ilauncher.data.livetv.LiveTvAudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvAudioQualityTest {
    private fun track(
        number: Int,
        language: String = "deu",
        channels: Int = 2,
        mimeType: String? = MimeTypes.AUDIO_AAC,
        default: Boolean = false,
        label: String? = null,
        supported: Boolean = true,
        codecs: String? = null,
    ) = LiveTvAudioTrack(
        groupIndex = 0,
        trackIndex = number,
        language = language,
        label = label,
        roleFlags = 0,
        selectionFlags = if (default) C.SELECTION_FLAG_DEFAULT else 0,
        channelCount = channels,
        supported = supported,
        selected = false,
        sampleMimeType = mimeType,
        codecs = codecs,
    )

    private fun choose(
        vararg tracks: LiveTvAudioTrack,
        quality: LiveTvAudioQuality = LiveTvAudioQuality.BestAvailable,
        surround: Boolean = true,
    ): LiveTvAudioTrack? = LiveTvAudioSelection.chooseAutomatic(
        tracks.toList(), LiveTvAudioLanguage.German, true, quality, surround,
    )

    @Test fun germanSurroundBeatsGermanDefaultStereo() {
        val stereo = track(0, default = true)
        val surround = track(1, channels = 6, mimeType = MimeTypes.AUDIO_AC3)
        assertEquals(surround, choose(stereo, surround))
    }

    @Test fun germanStereoBeatsEnglishAtmos() {
        val german = track(0)
        val english = track(1, language = "eng", channels = 6, mimeType = MimeTypes.AUDIO_E_AC3_JOC)
        assertEquals(german, choose(english, german))
    }

    @Test fun descriptiveSurroundNeverOutranksNormalGermanStereo() {
        val descriptive = track(0, channels = 6, mimeType = MimeTypes.AUDIO_E_AC3_JOC, label = "Hörfilm")
        val normal = track(1)
        assertEquals(normal, choose(descriptive, normal))
    }

    @Test fun atmosWinsOnComparableLanguageAndIsNotClaimedForOrdinaryEac3() {
        val ac3 = track(0, channels = 6, mimeType = MimeTypes.AUDIO_AC3)
        val atmos = track(1, channels = 6, mimeType = MimeTypes.AUDIO_E_AC3_JOC)
        assertEquals(atmos, choose(ac3, atmos))
        assertTrue(LiveTvAudioSelection.isDolbyAtmos(atmos))
        assertTrue(LiveTvAudioSelection.displayLabel(atmos, 2).contains("Dolby Atmos"))
        assertFalse(LiveTvAudioSelection.isDolbyAtmos(track(2, mimeType = MimeTypes.AUDIO_E_AC3)))
        assertFalse(LiveTvAudioSelection.isDolbyAtmos(track(3, mimeType = MimeTypes.AUDIO_TRUEHD)))
    }

    @Test fun codecStringEcPlus3IsDetectable() {
        assertTrue(LiveTvAudioSelection.isDolbyAtmos(
            track(0, mimeType = null, codecs = "ec+3"),
        ))
    }

    @Test fun unsupportedSurroundFallsBackToStereo() {
        val unsupported = track(0, channels = 6, supported = false)
        val stereo = track(1)
        assertEquals(stereo, choose(unsupported, stereo))
    }

    @Test fun senderStandardModeHonoursDefault() {
        val stereo = track(0, default = true)
        val surround = track(1, channels = 6, mimeType = MimeTypes.AUDIO_E_AC3)
        assertEquals(stereo, choose(stereo, surround, quality = LiveTvAudioQuality.SenderDefault))
    }

    @Test fun surroundDisabledPrefersStereo() {
        val stereo = track(0)
        val surround = track(1, channels = 6, mimeType = MimeTypes.AUDIO_E_AC3_JOC)
        assertEquals(stereo, choose(surround, stereo, surround = false))
    }

    @Test fun unknownPreferenceDefaultsToBestAvailable() {
        assertEquals(LiveTvAudioQuality.BestAvailable, LiveTvAudioQuality.fromStored(null))
        assertEquals(LiveTvAudioQuality.BestAvailable, LiveTvAudioQuality.fromStored("UNKNOWN"))
        assertEquals(LiveTvAudioQuality.SenderDefault, LiveTvAudioQuality.fromStored("SenderDefault"))
    }
}
