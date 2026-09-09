package com.andreassamitsch.joyntv

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val UPDATE_METADATA_URL =
    "https://raw.githubusercontent.com/andreassamitsch/I-Launcher-/joyn-downloads/joyn-update.json"
private const val UPDATE_FILE_NAME = "Joyn-TV-update.apk"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val PREFS_NAME = "joyn_tv_updates"
private const val KEY_DOWNLOAD_ID = "download_id"
private const val KEY_VERSION_CODE = "version_code"
private const val KEY_VERSION_NAME = "version_name"
private const val KEY_APK_URL = "apk_url"
private const val KEY_SHA256 = "sha256"

data class JoynUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

sealed interface JoynUpdateState {
    data object Idle : JoynUpdateState
    data object Checking : JoynUpdateState
    data class UpToDate(val versionName: String) : JoynUpdateState
    data class Available(val info: JoynUpdateInfo) : JoynUpdateState
    data class Downloading(val info: JoynUpdateInfo, val progressPercent: Int?) : JoynUpdateState
    data class ReadyToInstall(val info: JoynUpdateInfo) : JoynUpdateState
    data class Error(val message: String) : JoynUpdateState
}

sealed interface JoynInstallResult {
    data object Started : JoynInstallResult
    data object PermissionRequired : JoynInstallResult
    data class Error(val message: String) : JoynInstallResult
}

internal object JoynUpdateVersionPolicy {
    fun isNewer(remoteVersionCode: Int, localVersionCode: Int): Boolean =
        remoteVersionCode > localVersionCode
}

class JoynUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val updateFile: File?
        get() = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(UPDATE_FILE_NAME)

    private val _state = MutableStateFlow<JoynUpdateState>(JoynUpdateState.Idle)
    val state: StateFlow<JoynUpdateState> = _state.asStateFlow()

    suspend fun checkForUpdates() {
        _state.value = JoynUpdateState.Checking
        runCatching {
            withContext(Dispatchers.IO) {
                val url = "$UPDATE_METADATA_URL?check=${System.currentTimeMillis()}"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7_000
                    readTimeout = 7_000
                    instanceFollowRedirects = true
                    useCaches = false
                    defaultUseCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
                    setRequestProperty("Pragma", "no-cache")
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        error("Update-Server antwortet mit HTTP ${connection.responseCode}")
                    }
                    parseUpdateInfo(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            }
        }.onSuccess { info ->
            if (!JoynUpdateVersionPolicy.isNewer(info.versionCode, BuildConfig.VERSION_CODE)) {
                clearStoredDownload(removeDownload = false)
                _state.value = JoynUpdateState.UpToDate(BuildConfig.VERSION_NAME)
                return@onSuccess
            }

            val storedInfo = readStoredInfo()
            val storedId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
            if (storedId > 0L && storedInfo?.versionCode == info.versionCode) {
                queryDownloadState(info, storedId)
            } else {
                if (storedId > 0L) clearStoredDownload(removeDownload = true)
                _state.value = JoynUpdateState.Available(info)
            }
        }.onFailure { error ->
            _state.value = JoynUpdateState.Error(error.message ?: "Update-Prüfung fehlgeschlagen.")
        }
    }

    fun startDownload(info: JoynUpdateInfo) {
        clearStoredDownload(removeDownload = true)
        updateFile?.delete()

        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Joyn TV ${info.versionName}")
            setDescription("Joyn TV Update wird heruntergeladen")
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
            _state.value = JoynUpdateState.Downloading(info, progressPercent = null)
        }.onFailure { error ->
            _state.value = JoynUpdateState.Error(error.message ?: "Update-Download konnte nicht gestartet werden.")
        }
    }

    fun refreshDownloadState() {
        val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        val info = when (val current = _state.value) {
            is JoynUpdateState.Downloading -> current.info
            is JoynUpdateState.ReadyToInstall -> current.info
            else -> readStoredInfo()
        }
        if (id > 0L && info != null) queryDownloadState(info, id)
    }

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivitySafely(intent) || startActivitySafely(
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    suspend fun installDownloadedUpdate(): JoynInstallResult {
        refreshDownloadState()
        val ready = _state.value as? JoynUpdateState.ReadyToInstall
            ?: return JoynInstallResult.Error("Das Update ist noch nicht vollständig heruntergeladen.")
        val file = updateFile?.takeIf(File::isFile)
            ?: return JoynInstallResult.Error("Die heruntergeladene APK wurde nicht gefunden.")

        if (!canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return JoynInstallResult.PermissionRequired
        }

        val verification = withContext(Dispatchers.IO) {
            verifyDownloadedApk(file, ready.info)
        }
        if (verification != null) {
            clearStoredDownload(removeDownload = true)
            _state.value = JoynUpdateState.Error(verification)
            return JoynInstallResult.Error(verification)
        }

        val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        val apkUri = if (id > 0L) downloadManager.getUriForDownloadedFile(id) else null
            ?: return JoynInstallResult.Error("Installationsdatei konnte nicht geöffnet werden.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return runCatching {
                withContext(Dispatchers.IO) { JoynPackageSessionUpdater(appContext).install(apkUri) }
                JoynInstallResult.Started
            }.getOrElse { error ->
                JoynInstallResult.Error(error.message ?: "Installation konnte nicht gestartet werden.")
            }
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (startActivitySafely(installIntent)) {
            JoynInstallResult.Started
        } else {
            JoynInstallResult.Error("Auf diesem Gerät wurde kein APK-Installer gefunden.")
        }
    }

    private fun verifyDownloadedApk(file: File, info: JoynUpdateInfo): String? {
        if (!verifySha256(file, info.sha256)) {
            return "Die SHA-256-Prüfsumme der Update-APK stimmt nicht."
        }

        val archive = packageArchiveInfo(file)
            ?: return "Die Update-Datei ist keine lesbare Android-APK."
        if (archive.packageName != appContext.packageName) {
            return "Die Update-APK gehört zu einem anderen App-Paket."
        }

        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        if (archiveVersionCode != info.versionCode.toLong() || archiveVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            return "Die Versionsdaten der Update-APK sind nicht gültig."
        }

        val installed = appContext.packageManager.getPackageInfoCompat(appContext.packageName)
            ?: return "Die Signatur der installierten App konnte nicht gelesen werden."
        val installedCerts = certificateDigests(installed)
        val archiveCerts = certificateDigests(archive)
        if (installedCerts.isEmpty() || archiveCerts.isEmpty() || installedCerts.intersect(archiveCerts).isEmpty()) {
            return "Die Update-APK ist nicht mit dem Signierschlüssel dieser Installation signiert."
        }
        return null
    }

    private fun packageArchiveInfo(file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        }.getOrNull()

    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }

    private fun queryDownloadState(info: JoynUpdateInfo, id: Long) {
        val cursor = runCatching { downloadManager.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
        if (cursor == null) {
            _state.value = JoynUpdateState.Error("Downloadstatus konnte nicht gelesen werden.")
            return
        }
        cursor.use {
            if (!it.moveToFirst()) {
                clearStoredDownload(removeDownload = false)
                _state.value = JoynUpdateState.Available(info)
                return
            }
            when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> _state.value = JoynUpdateState.ReadyToInstall(info)
                DownloadManager.STATUS_FAILED -> {
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    clearStoredDownload(removeDownload = false)
                    _state.value = JoynUpdateState.Error("Update-Download fehlgeschlagen (Code $reason).")
                }
                else -> {
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val progress = if (total > 0L && downloaded >= 0L) {
                        ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    } else null
                    _state.value = JoynUpdateState.Downloading(info, progress)
                }
            }
        }
    }

    private fun parseUpdateInfo(json: String): JoynUpdateInfo {
        val obj = JSONObject(json)
        val versionCode = obj.getInt("versionCode")
        val versionName = obj.getString("versionName").trim()
        val apkUrl = obj.getString("apkUrl").trim()
        val sha256 = obj.getString("sha256").trim().lowercase()
        require(versionCode > 0) { "Ungültiger versionCode im Update-Manifest." }
        require(versionName.isNotBlank()) { "Leerer versionName im Update-Manifest." }
        require(apkUrl.startsWith("https://")) { "Update-APK muss über HTTPS geladen werden." }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Ungültige SHA-256-Prüfsumme." }
        return JoynUpdateInfo(versionCode, versionName, apkUrl, sha256)
    }

    private fun readStoredInfo(): JoynUpdateInfo? {
        val code = preferences.getInt(KEY_VERSION_CODE, -1)
        val name = preferences.getString(KEY_VERSION_NAME, null)
        val url = preferences.getString(KEY_APK_URL, null)
        val hash = preferences.getString(KEY_SHA256, null)
        if (code <= 0 || name.isNullOrBlank() || url.isNullOrBlank() || hash.isNullOrBlank()) return null
        return JoynUpdateInfo(code, name, url, hash)
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return actual.equals(expected, ignoreCase = true)
    }

    private fun clearStoredDownload(removeDownload: Boolean) {
        val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        if (removeDownload && id > 0L) runCatching { downloadManager.remove(id) }
        preferences.edit().clear().apply()
        updateFile?.delete()
    }

    private fun startActivitySafely(intent: Intent): Boolean =
        runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
}
