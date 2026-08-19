package com.andreassamitsch.ilauncher.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallPolicyTest {
    @Test
    fun android13AndNewerUsePackageSessionInstaller() {
        assertTrue(UpdateInstallPolicy.shouldUseRestrictedSettingsSafeSession(sdkInt = 33))
        assertTrue(UpdateInstallPolicy.shouldUseRestrictedSettingsSafeSession(sdkInt = 36))
    }

    @Test
    fun android12AndOlderKeepLegacyInstaller() {
        assertFalse(UpdateInstallPolicy.shouldUseRestrictedSettingsSafeSession(sdkInt = 32))
        assertFalse(UpdateInstallPolicy.shouldUseRestrictedSettingsSafeSession(sdkInt = 26))
    }
}
