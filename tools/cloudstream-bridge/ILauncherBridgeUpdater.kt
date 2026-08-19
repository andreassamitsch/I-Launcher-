package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Development updater dedicated to the reproducible I Launcher CloudStream bridge build. */
internal object ILauncherBridgeUpdater {
    private const val TAG = "ILauncherBridgeUpdate"
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/andreassamitsch/I-Launcher-/cloudstream-downloads/update.json"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val automaticCheckStarted = AtomicBoolean(false)

    @Serializable
    private data class UpdateManifest(
        @JsonProperty("versionCode") @SerialName("versionCode") val versionCode: Long,
        @JsonProperty("versionName") @SerialName("versionName") val versionName: String,
        @JsonProperty("apkUrl") @SerialName("apkUrl") val apkUrl: String,
        @JsonProperty("sha256") @SerialName("sha256") val sha256: String,
        @JsonProperty("sourceSha") @SerialName("sourceSha") val sourceSha: String? = null,
    )

    fun checkForUpdates(activity: Activity, automatic: Boolean) {
        if (automatic) {
            if (!ILauncherBridgePreferences.isAutoUpdateEnabled(activity)) return
            if (!automaticCheckStarted.compareAndSet(false, true)) return
        }

        ioSafe {
            val manifest = runCatching {
                parseJson<UpdateManifest>(app.get(MANIFEST_URL).text)
            }.onFailure { throwable ->
                logError(throwable)
                Log.w(TAG, "update check failed (${throwable.javaClass.simpleName})")
            }.getOrNull()

            if (manifest == null) {
                if (!automatic) main { showStatus(activity, "Update-Prüfung fehlgeschlagen", "Das Bridge-Update-Manifest konnte nicht geladen werden.") }
                return@ioSafe
            }

            val currentInfo = runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0)
            }.getOrNull() ?: return@ioSafe
            val currentVersionCode = PackageInfoCompat.getLongVersionCode(currentInfo)

            if (manifest.versionCode <= currentVersionCode) {
                Log.i(TAG, "up to date current=$currentVersionCode remote=${manifest.versionCode}")
                if (!automatic) {
                    main {
                        showStatus(
                            activity,
                            "Bridge ist aktuell",
                            "Installiert: ${currentInfo.versionName} (${currentVersionCode})\nVerfügbar: ${manifest.versionName} (${manifest.versionCode})",
                        )
                    }
                }
                return@ioSafe
            }

            Log.i(TAG, "update available current=$currentVersionCode remote=${manifest.versionCode}")
            main { showUpdateDialog(activity, currentInfo.versionName.orEmpty(), manifest) }
        }
    }

    private fun showUpdateDialog(activity: Activity, currentVersion: String, manifest: UpdateManifest) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle("I Launcher Bridge Update")
            .setMessage(
                "Installiert: $currentVersion\n" +
                    "Neu: ${manifest.versionName}\n\n" +
                    "Die APK stammt aus dem Development-Kanal des I-Launcher-Repositories und wird vor der Installation per SHA-256 geprüft.",
            )
            .setPositiveButton("Aktualisieren") { _, _ -> downloadAndInstall(activity, manifest) }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, manifest: UpdateManifest) {
        ioSafe {
            val apk = File.createTempFile("CloudStream-I-Launcher-Bridge-", ".apk", activity.cacheDir)
            val success = runCatching {
                apk.sink().buffer().use { sink ->
                    sink.writeAll(app.get(manifest.apkUrl).body.source())
                }
                val actualSha = sha256(apk)
                require(actualSha.equals(manifest.sha256, ignoreCase = true)) {
                    "SHA-256 mismatch"
                }
                true
            }.onFailure { throwable ->
                logError(throwable)
                Log.w(TAG, "download/install preparation failed (${throwable.javaClass.simpleName})")
            }.getOrDefault(false)

            if (!success) {
                apk.delete()
                main { showStatus(activity, "Update fehlgeschlagen", "Die APK konnte nicht sicher heruntergeladen oder geprüft werden.") }
                return@ioSafe
            }

            main {
                runCatching { openApk(activity, apk) }
                    .onFailure { throwable ->
                        logError(throwable)
                        apk.delete()
                        showStatus(activity, "Installation fehlgeschlagen", "Der Android-Paketinstaller konnte nicht geöffnet werden.")
                    }
            }
        }
    }

    private fun openApk(activity: Activity, apk: File) {
        val contentUri: Uri = FileProvider.getUriForFile(
            activity,
            BuildConfig.APPLICATION_ID + ".provider",
            apk,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            },
        )
    }

    private fun showStatus(activity: Activity, title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
