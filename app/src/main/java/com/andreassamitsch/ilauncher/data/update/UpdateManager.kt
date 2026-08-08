package com.andreassamitsch.ilauncher.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.andreassamitsch.ilauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val UPDATE_METADATA_URL =
    "https://raw.githubusercontent.com/andreassamitsch/I-Launcher-/downloads/update.json"
private const val UPDATE_FILE_NAME = "I-Launcher-update.apk"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

private const val PREFS_NAME = "i_launcher_updates"
private const val KEY_DOWNLOAD_ID = "download_id"
private const val KEY_VERSION_CODE = "version_code"
private const val KEY_VERSION_NAME = "version_name"
private const val KEY_APK_URL = "apk_url"
private const val KEY_SHA256 = "sha256"

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String, val versionCode: Int) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(
        val info: UpdateInfo,
        val downloadId: Long,
        val progressPercent: Int?,
    ) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val downloadId: Long) : UpdateState
    data class Error(val message: String) : UpdateState
}

sealed interface InstallResult {
    data object Started : InstallResult
    data object PermissionRequired : InstallResult
    data class Error(val message: String) : InstallResult
}

object UpdateVersionPolicy {
    fun isNewer(remoteVersionCode: Int, localVersionCode: Int): Boolean =
        remoteVersionCode > localVersionCode
}

class UpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun checkForUpdates() {
        _state.value = UpdateState.Checking

        val result = runCatching {
            withContext(Dispatchers.IO) {
                val connection = (URL(UPDATE_METADATA_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                }

                try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        error("Update-Server antwortet mit HTTP $responseCode")
                    }
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    parseUpdateInfo(json)
                } finally {
                    connection.disconnect()
                }
            }
        }

        result.onSuccess { info ->
            if (!UpdateVersionPolicy.isNewer(info.versionCode, BuildConfig.VERSION_CODE)) {
                clearStoredDownload(removeDownload = false)
                _state.value = UpdateState.UpToDate(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
                return@onSuccess
            }

            val storedInfo = readStoredInfo()
            val storedDownloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
            if (storedDownloadId > 0L && storedInfo?.versionCode == info.versionCode) {
                queryDownloadState(info, storedDownloadId)
            } else {
                if (storedDownloadId > 0L) {
                    clearStoredDownload(removeDownload = true)
                }
                _state.value = UpdateState.Available(info)
            }
        }.onFailure { throwable ->
            _state.value = UpdateState.Error(
                throwable.message ?: "Update-Prüfung fehlgeschlagen.",
            )
        }
    }

    fun startDownload(info: UpdateInfo) {
        clearStoredDownload(removeDownload = true)

        val updateFile = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(UPDATE_FILE_NAME)
        updateFile?.delete()

        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("I Launcher ${info.versionName}")
            setDescription("I Launcher Update wird heruntergeladen")
            setMimeType(APK_MIME_TYPE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                UPDATE_FILE_NAME,
            )
        }

        runCatching {
            val id = downloadManager.enqueue(request)
            preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putInt(KEY_VERSION_CODE, info.versionCode)
                .putString(KEY_VERSION_NAME, info.versionName)
                .putString(KEY_APK_URL, info.apkUrl)
                .putString(KEY_SHA256, info.sha256)
                .apply()
            _state.value = UpdateState.Downloading(info, id, progressPercent = null)
        }.onFailure { throwable ->
            _state.value = UpdateState.Error(
                throwable.message ?: "Update-Download konnte nicht gestartet werden.",
            )
        }
    }

    fun refreshDownloadState() {
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        val info = when (val current = _state.value) {
            is UpdateState.Downloading -> current.info
            is UpdateState.ReadyToInstall -> current.info
            else -> readStoredInfo()
        }

        if (downloadId <= 0L || info == null) {
            return
        }
        queryDownloadState(info, downloadId)
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        val specificIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return startActivitySafely(specificIntent) ||
            startActivitySafely(
                Intent(Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
    }

    suspend fun installDownloadedUpdate(): InstallResult {
        refreshDownloadState()
        val ready = _state.value as? UpdateState.ReadyToInstall
            ?: return InstallResult.Error("Das Update ist noch nicht vollständig heruntergeladen.")

        if (!canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return InstallResult.PermissionRequired
        }

        val apkUri = downloadManager.getUriForDownloadedFile(ready.downloadId)
            ?: return InstallResult.Error("Die heruntergeladene APK wurde nicht gefunden.")

        val hashMatches = withContext(Dispatchers.IO) {
            verifySha256(apkUri, ready.info.sha256)
        }
        if (!hashMatches) {
            _state.value = UpdateState.Error(
                "Die Prüfsumme der heruntergeladenen APK stimmt nicht. Download verworfen.",
            )
            clearStoredDownload(removeDownload = true)
            return InstallResult.Error("APK-Prüfsumme stimmt nicht.")
        }

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (startActivitySafely(installIntent)) {
            return InstallResult.Started
        }

        val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (startActivitySafely(fallbackIntent)) {
            InstallResult.Started
        } else {
            InstallResult.Error("Auf diesem Gerät wurde kein APK-Installer gefunden.")
        }
    }

    private fun queryDownloadState(info: UpdateInfo, downloadId: Long) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = runCatching { downloadManager.query(query) }.getOrNull()
        if (cursor == null) {
            _state.value = UpdateState.Error("Downloadstatus konnte nicht gelesen werden.")
            return
        }

        cursor.use {
            if (!it.moveToFirst()) {
                clearStoredDownload(removeDownload = false)
                _state.value = UpdateState.Available(info)
                return
            }

            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    _state.value = UpdateState.ReadyToInstall(info, downloadId)
                }

                DownloadManager.STATUS_FAILED -> {
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    clearStoredDownload(removeDownload = false)
                    _state.value = UpdateState.Error("Update-Download fehlgeschlagen (Code $reason).")
                }

                else -> {
                    val total = it.getLong(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    )
                    val downloaded = it.getLong(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    )
                    val progress = if (total > 0L && downloaded >= 0L) {
                        ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    } else {
                        null
                    }
                    _state.value = UpdateState.Downloading(info, downloadId, progress)
                }
            }
        }
    }

    private fun parseUpdateInfo(json: String): UpdateInfo {
        val obj = JSONObject(json)
        val versionCode = obj.getInt("versionCode")
        val versionName = obj.getString("versionName").trim()
        val apkUrl = obj.getString("apkUrl").trim()
        val sha256 = obj.getString("sha256").trim().lowercase()

        require(versionCode > 0) { "Ungültiger versionCode im Update-Manifest." }
        require(versionName.isNotBlank()) { "Leerer versionName im Update-Manifest." }
        require(apkUrl.startsWith("https://")) { "Update-APK muss über HTTPS geladen werden." }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Ungültige SHA-256-Prüfsumme." }

        return UpdateInfo(versionCode, versionName, apkUrl, sha256)
    }

    private fun readStoredInfo(): UpdateInfo? {
        val versionCode = preferences.getInt(KEY_VERSION_CODE, -1)
        val versionName = preferences.getString(KEY_VERSION_NAME, null)
        val apkUrl = preferences.getString(KEY_APK_URL, null)
        val sha256 = preferences.getString(KEY_SHA256, null)
        if (versionCode <= 0 || versionName.isNullOrBlank() || apkUrl.isNullOrBlank() || sha256.isNullOrBlank()) {
            return null
        }
        return UpdateInfo(versionCode, versionName, apkUrl, sha256)
    }

    private fun verifySha256(uri: Uri, expectedHash: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        } ?: return false

        val actual = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        return actual.equals(expectedHash, ignoreCase = true)
    }

    private fun clearStoredDownload(removeDownload: Boolean) {
        val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        if (removeDownload && id > 0L) {
            runCatching { downloadManager.remove(id) }
        }
        preferences.edit().clear().apply()
    }

    private fun startActivitySafely(intent: Intent): Boolean {
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
