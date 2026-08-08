package com.andreassamitsch.ilauncher.system

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

object HomeLauncherManager {
    fun isDefaultHome(context: Context): Boolean {
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
        return resolveInfo?.activityInfo?.packageName == context.packageName
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

    private fun Context.startSafely(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
