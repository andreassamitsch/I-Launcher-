package com.andreassamitsch.ilauncher.data.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.andreassamitsch.ilauncher.model.InstalledApp

class InstalledAppsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun loadApps(): List<InstalledApp> {
        val candidates = buildList {
            addAll(queryLauncherActivities(Intent.CATEGORY_LEANBACK_LAUNCHER, isTvApp = true))
            addAll(queryLauncherActivities(Intent.CATEGORY_LAUNCHER, isTvApp = false))
        }

        return AppCatalogPolicy
            .select(candidates, ownPackageName = appContext.packageName)
            .mapNotNull(::toInstalledApp)
    }

    fun launch(app: InstalledApp): Boolean {
        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: packageManager.getLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_MAIN).setComponent(app.componentName)

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            appContext.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun queryLauncherActivities(category: String, isTvApp: Boolean): List<AppCandidate> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return queryIntentActivities(intent).map { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo
            AppCandidate(
                packageName = activityInfo.packageName,
                activityName = activityInfo.name,
                label = resolveInfo.loadLabel(packageManager).toString(),
                isTvApp = isTvApp,
            )
        }
    }

    private fun toInstalledApp(candidate: AppCandidate): InstalledApp? {
        return runCatching {
            val component = ComponentName(candidate.packageName, candidate.activityName)
            InstalledApp(
                packageName = candidate.packageName,
                label = candidate.label,
                componentName = component,
                icon = packageManager.getActivityIcon(component).toBitmap(128),
            )
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun queryIntentActivities(intent: Intent): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun Drawable.toBitmap(sizePx: Int): Bitmap {
        if (this is BitmapDrawable && bitmap.width > 0 && bitmap.height > 0) {
            return bitmap
        }

        val targetWidth = intrinsicWidth.takeIf { it > 0 } ?: sizePx
        val targetHeight = intrinsicHeight.takeIf { it > 0 } ?: sizePx
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
}
