package com.andreassamitsch.ilauncher.data.handoff

import android.content.Context
import android.graphics.drawable.Drawable
import com.andreassamitsch.ilauncher.data.cloudstream.CloudStreamSearchLauncher
import com.andreassamitsch.ilauncher.data.kodi.KodiSearchLauncher

enum class ContentSearchTarget(val displayName: String) {
    CloudStream("CloudStream"),
    Kodi("Kodi"),
}

class ContentSearchHandoff(context: Context) {
    private val appContext = context.applicationContext
    private val cloudStream = CloudStreamSearchLauncher(appContext)
    private val kodi = KodiSearchLauncher(appContext)

    fun availableTargets(): List<ContentSearchTarget> = buildList {
        if (cloudStream.isAvailable()) add(ContentSearchTarget.CloudStream)
        if (kodi.isAvailable()) add(ContentSearchTarget.Kodi)
    }

    fun appIcon(target: ContentSearchTarget): Drawable? {
        val packageName = when (target) {
            ContentSearchTarget.CloudStream -> cloudStream.resolvedPackageName()
            ContentSearchTarget.Kodi -> kodi.resolvedPackageName()
        } ?: return null
        return runCatching { appContext.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    fun launch(target: ContentSearchTarget, title: String): Boolean {
        val query = normalizeContentSearchQuery(title)
        if (query.isBlank()) return false
        return when (target) {
            ContentSearchTarget.CloudStream -> cloudStream.launch(query)
            ContentSearchTarget.Kodi -> kodi.launch(query)
        }
    }
}

internal fun normalizeContentSearchQuery(value: String): String =
    value.trim().replace(Regex("\\s+"), " ")