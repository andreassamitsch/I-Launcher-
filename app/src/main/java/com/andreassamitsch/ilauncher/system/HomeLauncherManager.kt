package com.andreassamitsch.ilauncher.system

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

data class InstallSourceStatus(
    val label: String,
    val installerPackageName: String?,
    val restrictedSettingsLikely: Boolean,
)

object HomeLauncherManager {
    fun defaultHomePackageName(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfo?.activityInfo?.packageName
    }

    fun isDefaultHome(context: Context): Boolean =
        defaultHomePackageName(context) == context.packageName

    fun isHomeRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { roleManager.isRoleAvailable(RoleManager.ROLE_HOME) }.getOrDefault(false)
    }

    fun isHomeRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return isDefaultHome(context)
        }
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return isDefaultHome(context)
        return runCatching {
            roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }.getOrDefault(isDefaultHome(context))
    }

    fun isHomeButtonOverrideEnabled(context: Context): Boolean {
        val expected = ComponentName(context, HomeButtonAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        return enabledServices
            .split(':')
            .asSequence()
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    fun installSourceStatus(context: Context): InstallSourceStatus {
        return runCatching {
            val packageManager = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val info = packageManager.getInstallSourceInfo(context.packageName)
                val label = when (info.packageSource) {
                    PackageInstaller.PACKAGE_SOURCE_STORE -> "App-Store"
                    PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> "lokale APK-Datei"
                    PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> "heruntergeladene APK-Datei"
                    PackageInstaller.PACKAGE_SOURCE_OTHER -> "andere Installationsquelle"
                    else -> "nicht angegebene Installationsquelle"
                }
                InstallSourceStatus(
                    label = label,
                    installerPackageName = info.installingPackageName,
                    restrictedSettingsLikely =
                        info.packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                            info.packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = packageManager.getInstallSourceInfo(context.packageName)
                InstallSourceStatus(
                    label = "Installationsquelle nicht klassifizierbar (Android < 13)",
                    installerPackageName = info.installingPackageName,
                    restrictedSettingsLikely = false,
                )
            } else {
                @Suppress("DEPRECATION")
                val installer = packageManager.getInstallerPackageName(context.packageName)
                InstallSourceStatus(
                    label = "Installationsquelle nicht klassifizierbar (Android < 11)",
                    installerPackageName = installer,
                    restrictedSettingsLikely = false,
                )
            }
        }.getOrElse {
            InstallSourceStatus(
                label = "Installationsquelle unbekannt",
                installerPackageName = null,
                restrictedSettingsLikely = false,
            )
        }
    }

    fun openDefaultHomeSelection(context: Context): Boolean {
        val roleIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } else {
                null
            }
        } else {
            null
        }

        if (roleIntent != null && context.startSafely(roleIntent)) {
            return true
        }

        return context.startSafely(Intent(Settings.ACTION_HOME_SETTINGS)) ||
            context.startSafely(Intent(Settings.ACTION_SETTINGS))
    }

    fun openAccessibilitySettings(context: Context): Boolean {
        return context.startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) ||
            context.startSafely(Intent(Settings.ACTION_SETTINGS))
    }

    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        return context.startSafely(intent) || context.startSafely(Intent(Settings.ACTION_APPLICATION_SETTINGS))
    }

    private fun Context.startSafely(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
