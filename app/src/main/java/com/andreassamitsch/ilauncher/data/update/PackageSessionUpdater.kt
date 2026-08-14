package com.andreassamitsch.ilauncher.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log

internal object UpdateInstallPolicy {
    const val PACKAGE_SESSION_MIN_SDK = 33

    fun shouldUseRestrictedSettingsSafeSession(sdkInt: Int): Boolean =
        sdkInt >= PACKAGE_SESSION_MIN_SDK
}

/**
 * Installs an already verified self-update through PackageInstaller directly.
 *
 * Android 13-era package installers can mark APKs handed to ACTION_INSTALL_PACKAGE as a local or
 * downloaded file and reset access_restricted_settings. A direct session lets us describe the
 * update as PACKAGE_SOURCE_OTHER instead, matching the relevant source semantics of an ADB-style
 * install while still requiring Android's normal user confirmation.
 */
internal class PackageSessionUpdater(private val context: Context) {
    fun install(apkUri: Uri) {
        check(UpdateInstallPolicy.shouldUseRestrictedSettingsSafeSession(Build.VERSION.SDK_INT)) {
            "PackageInstaller session path requires Android 13 or newer."
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }

        val sessionId = packageInstaller.createSession(params)
        var committed = false
        try {
            packageInstaller.openSession(sessionId).use { session ->
                context.contentResolver.openInputStream(apkUri)?.use { input ->
                    session.openWrite(SESSION_APK_NAME, 0L, -1L).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                } ?: error("Die heruntergeladene APK konnte nicht geöffnet werden.")

                val statusIntent = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
                    action = ACTION_UPDATE_INSTALL_STATUS
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
                val statusPendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(statusPendingIntent.intentSender)
                committed = true
            }
        } finally {
            if (!committed) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
        }
    }

    private companion object {
        const val SESSION_APK_NAME = "base.apk"
    }
}

class UpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_INSTALL_STATUS) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                if (confirmationIntent == null) {
                    Log.e(TAG, "PackageInstaller requested user action without confirmation intent")
                    return
                }
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmationIntent) }
                    .onFailure { error ->
                        Log.e(TAG, "Could not open package install confirmation", error)
                    }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "PackageInstaller update completed")
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.take(MAX_STATUS_MESSAGE_LENGTH)
                    .orEmpty()
                Log.e(TAG, "PackageInstaller update failed: status=$status message=$message")
            }
        }
    }

    private companion object {
        const val TAG = "ILauncherUpdate"
        const val MAX_STATUS_MESSAGE_LENGTH = 240
    }
}

internal const val ACTION_UPDATE_INSTALL_STATUS =
    "com.andreassamitsch.ilauncher.action.UPDATE_INSTALL_STATUS"
internal const val EXTRA_SESSION_ID = "update_session_id"
