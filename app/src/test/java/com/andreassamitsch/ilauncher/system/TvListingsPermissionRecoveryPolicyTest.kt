package com.andreassamitsch.ilauncher.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvListingsPermissionRecoveryPolicyTest {
    @Test
    fun recoversMissingPermissionOnceAfterDevelopmentUpdate() {
        assertTrue(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                isUpdatedInstall = true,
                initialRequestShown = true,
                lastGrantedVersionCode = 101,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
    }

    @Test
    fun bootstrapsExistingDevelopmentInstallWithoutGrantHistory() {
        assertTrue(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                isUpdatedInstall = true,
                initialRequestShown = false,
                lastGrantedVersionCode = -1,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
        assertTrue(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                isUpdatedInstall = false,
                initialRequestShown = true,
                lastGrantedVersionCode = -1,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
    }

    @Test
    fun doesNotRecoverWhenPermissionSurvivedUpdate() {
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = true,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                isUpdatedInstall = true,
                initialRequestShown = true,
                lastGrantedVersionCode = 101,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
    }

    @Test
    fun doesNotLoopRecoveryWithinSameVersion() {
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                isUpdatedInstall = true,
                initialRequestShown = true,
                lastGrantedVersionCode = 101,
                lastRecoveryAttemptVersionCode = 102,
            ),
        )
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                isUpdatedInstall = true,
                initialRequestShown = true,
                lastGrantedVersionCode = -1,
                lastRecoveryAttemptVersionCode = 102,
            ),
        )
    }

    @Test
    fun sameVersionRevokeIsNotTreatedAsTrackedUpdateLoss() {
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                isUpdatedInstall = false,
                initialRequestShown = true,
                lastGrantedVersionCode = 102,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
    }

    @Test
    fun freshInstallAndReleaseBuildDoNotUseDevelopmentRecovery() {
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                isUpdatedInstall = false,
                initialRequestShown = false,
                lastGrantedVersionCode = -1,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = false,
                currentVersionCode = 102,
                isUpdatedInstall = true,
                initialRequestShown = true,
                lastGrantedVersionCode = 101,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
    }
}
