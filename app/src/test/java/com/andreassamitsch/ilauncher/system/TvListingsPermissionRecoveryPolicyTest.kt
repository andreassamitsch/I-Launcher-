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
                lastGrantedVersionCode = 101,
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
                lastGrantedVersionCode = 101,
                lastRecoveryAttemptVersionCode = 102,
            ),
        )
    }

    @Test
    fun sameVersionRevokeIsNotTreatedAsUpdateLoss() {
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                lastGrantedVersionCode = 102,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
    }

    @Test
    fun firstInstallAndReleaseBuildDoNotUseDevelopmentRecovery() {
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = true,
                currentVersionCode = 102,
                lastGrantedVersionCode = -1,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
        assertFalse(
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = false,
                currentVersionCode = 102,
                lastGrantedVersionCode = 101,
                lastRecoveryAttemptVersionCode = -1,
            ),
        )
    }
}
