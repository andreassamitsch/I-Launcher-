package com.andreassamitsch.joyntv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log

internal class JoynPackageSessionUpdater(private val context: Context) {
    fun install(apkUri: Uri) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "PackageInstaller session path requires Android 13 or newer."
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                context.contentResolver.openInputStream(apkUri)?.use { input ->
                    session.openWrite("base.apk", 0L, -1L).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                } ?: error("Die heruntergeladene APK konnte nicht geöffnet werden.")

                val statusIntent = Intent(context, JoynUpdateInstallStatusReceiver::class.java).apply {
                    action = ACTION_JOYN_UPDATE_INSTALL_STATUS
                    putExtra(EXTRA_JOYN_UPDATE_SESSION_ID, sessionId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }
}

class JoynUpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_JOYN_UPDATE_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                if (confirmationIntent == null) {
                    Log.e(TAG, "PackageInstaller requested confirmation without an intent")
                    return
                }
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmationIntent) }
                    .onFailure { error -> Log.e(TAG, "Could not open update confirmation", error) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Joyn TV update completed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.take(240)
                    .orEmpty()
                Log.e(TAG, "Joyn TV update failed: status=$status message=$message")
            }
        }
    }

    private companion object {
        const val TAG = "JoynTvUpdate"
    }
}

internal const val ACTION_JOYN_UPDATE_INSTALL_STATUS =
    "com.andreassamitsch.joyntv.action.UPDATE_INSTALL_STATUS"
internal const val EXTRA_JOYN_UPDATE_SESSION_ID = "update_session_id"
