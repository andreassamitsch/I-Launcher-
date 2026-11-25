package com.andreassamitsch.ilauncher.ui.settings

import com.andreassamitsch.ilauncher.data.update.UpdateInfo
import com.andreassamitsch.ilauncher.data.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePopupPolicyTest {
    private val info = UpdateInfo(42, "dev.42", "https://example.invalid/app.apk", "0".repeat(64), true)

    @Test fun compatibleUpdatesKeepSameVersionAcrossDownloadAndInstall() {
        assertEquals(42, updatePromptVersion(UpdateState.Available(info)))
        assertEquals(42, updatePromptVersion(UpdateState.Downloading(info, 1L, 50)))
        assertEquals(42, updatePromptVersion(UpdateState.ReadyToInstall(info, 1L)))
    }

    @Test fun incompatibleAndNonActionableStatesDoNotPrompt() {
        assertNull(updatePromptVersion(UpdateState.Available(info.copy(updateCompatible = false))))
        assertNull(updatePromptVersion(UpdateState.SigningRequired(info)))
        assertNull(updatePromptVersion(UpdateState.UpToDate("dev.42", 42)))
        assertNull(updatePromptVersion(UpdateState.Idle))
        assertNull(updatePromptVersion(UpdateState.Checking))
        assertNull(updatePromptVersion(UpdateState.Error("offline")))
    }
}
