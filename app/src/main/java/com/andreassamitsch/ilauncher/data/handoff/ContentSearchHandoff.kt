package com.andreassamitsch.ilauncher.data.handoff

import android.content.Context
import android.graphics.drawable.Drawable
import com.andreassamitsch.ilauncher.data.cloudstream.CloudStreamLaunchMode
import com.andreassamitsch.ilauncher.data.cloudstream.CloudStreamLauncher
import com.andreassamitsch.ilauncher.data.kodi.KodiSearchLauncher
import com.andreassamitsch.ilauncher.model.MediaItem

enum class ContentSearchTarget(val displayName: String) {
    CloudStream("CloudStream"),
    Kodi("Kodi"),
}

enum class ContentHandoffMode {
    DirectPlay,
    Search,
}

class ContentSearchHandoff(context: Context) {
    private val appContext = context.applicationContext
    private val cloudStream = CloudStreamLauncher(appContext)
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

    fun mode(target: ContentSearchTarget, item: MediaItem): ContentHandoffMode = when (target) {
        ContentSearchTarget.CloudStream -> when (cloudStream.actionMode(item)) {
            CloudStreamLaunchMode.DirectPlay -> ContentHandoffMode.DirectPlay
            CloudStreamLaunchMode.SearchFallback,
            null,
            -> ContentHandoffMode.Search
        }
        ContentSearchTarget.Kodi -> ContentHandoffMode.Search
    }

    fun launch(target: ContentSearchTarget, item: MediaItem): Boolean = when (target) {
        ContentSearchTarget.CloudStream -> cloudStream.launch(item) != null
        ContentSearchTarget.Kodi -> {
            val query = normalizeContentSearchQuery(item.title)
            query.isNotBlank() && kodi.launch(query)
        }
    }
}

internal fun normalizeContentSearchQuery(value: String): String =
    value.trim().replace(Regex("\\s+"), " ")
