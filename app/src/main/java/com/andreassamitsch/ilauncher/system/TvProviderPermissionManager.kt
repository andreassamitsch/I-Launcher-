package com.andreassamitsch.ilauncher.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.andreassamitsch.ilauncher.BuildConfig

internal object TvListingsPermissionRecoveryPolicy {
    fun shouldRecoverAfterUpdate(
        granted: Boolean,
        isDevelopmentBuild: Boolean,
        currentVersionCode: Int,
        lastGrantedVersionCode: Int,
        lastRecoveryAttemptVersionCode: Int,
    ): Boolean =
        !granted &&
            isDevelopmentBuild &&
            lastGrantedVersionCode > 0 &&
            currentVersionCode > lastGrantedVersionCode &&
            lastRecoveryAttemptVersionCode != currentVersionCode
}

object TvProviderPermissionManager {
    const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

    private const val PREFS_NAME = "tv_provider_permissions"
    private const val KEY_INITIAL_REQUEST_SHOWN = "read_tv_listings_initial_request_shown"
    private const val KEY_LAST_GRANTED_VERSION_CODE = "read_tv_listings_last_granted_version_code"
    private const val KEY_LAST_RECOVERY_ATTEMPT_VERSION_CODE =
        "read_tv_listings_last_recovery_attempt_version_code"

    fun hasReadTvListings(context: Context): Boolean {
        val granted = isReadTvListingsGranted(context)
        if (granted) {
            recordGrantedForCurrentVersion(context)
        }
        return granted
    }

    /**
     * The first install asks once as before. Development builds also get one automatic recovery
     * request per version when READ_TV_LISTINGS was granted in an older build but disappeared after
     * an APK update. A deliberate revoke inside the same version therefore does not cause a prompt
     * loop, while an OEM/package-installer permission reset no longer requires another manual ADB
     * grant on every development update.
     */
    fun shouldShowInitialRequest(context: Context): Boolean {
        val granted = isReadTvListingsGranted(context)
        if (granted) {
            recordGrantedForCurrentVersion(context)
            return false
        }

        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (
            TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
                granted = false,
                isDevelopmentBuild = BuildConfig.DEBUG,
                currentVersionCode = BuildConfig.VERSION_CODE,
                lastGrantedVersionCode = preferences.getInt(KEY_LAST_GRANTED_VERSION_CODE, -1),
                lastRecoveryAttemptVersionCode = preferences.getInt(
                    KEY_LAST_RECOVERY_ATTEMPT_VERSION_CODE,
                    -1,
                ),
            )
        ) {
            return true
        }

        return !preferences.getBoolean(KEY_INITIAL_REQUEST_SHOWN, false)
    }

    fun markInitialRequestShown(context: Context) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val granted = isReadTvListingsGranted(context)
        val isRecoveryAttempt = TvListingsPermissionRecoveryPolicy.shouldRecoverAfterUpdate(
            granted = granted,
            isDevelopmentBuild = BuildConfig.DEBUG,
            currentVersionCode = BuildConfig.VERSION_CODE,
            lastGrantedVersionCode = preferences.getInt(KEY_LAST_GRANTED_VERSION_CODE, -1),
            lastRecoveryAttemptVersionCode = preferences.getInt(
                KEY_LAST_RECOVERY_ATTEMPT_VERSION_CODE,
                -1,
            ),
        )

        preferences.edit().apply {
            putBoolean(KEY_INITIAL_REQUEST_SHOWN, true)
            if (isRecoveryAttempt) {
                putInt(KEY_LAST_RECOVERY_ATTEMPT_VERSION_CODE, BuildConfig.VERSION_CODE)
            }
        }.apply()
    }

    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun isReadTvListingsGranted(context: Context): Boolean =
        context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    private fun recordGrantedForCurrentVersion(context: Context) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getInt(KEY_LAST_GRANTED_VERSION_CODE, -1) == BuildConfig.VERSION_CODE) {
            return
        }

        preferences.edit()
            .putInt(KEY_LAST_GRANTED_VERSION_CODE, BuildConfig.VERSION_CODE)
            .remove(KEY_LAST_RECOVERY_ATTEMPT_VERSION_CODE)
            .apply()
    }
}
