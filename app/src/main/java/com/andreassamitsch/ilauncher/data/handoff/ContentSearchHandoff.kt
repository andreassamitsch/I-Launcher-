package com.andreassamitsch.ilauncher.data.handoff

import android.content.Context
import com.andreassamitsch.ilauncher.data.cloudstream.CloudStreamSearchLauncher
import com.andreassamitsch.ilauncher.data.kodi.KodiSearchLauncher

enum class ContentSearchTarget(val displayName: String) {
    CloudStream("CloudStream"),
    Kodi("Kodi"),
}

class ContentSearchHandoff(context: Context) {
    private val cloudStream = CloudStreamSearchLauncher(context.applicationContext)
    private val kodi = KodiSearchLauncher(context.applicationContext)

    fun availableTargets(): List<ContentSearchTarget> = buildList {
        if (cloudStream.isAvailable()) add(ContentSearchTarget.CloudStream)
        if (kodi.isAvailable()) add(ContentSearchTarget.Kodi)
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
