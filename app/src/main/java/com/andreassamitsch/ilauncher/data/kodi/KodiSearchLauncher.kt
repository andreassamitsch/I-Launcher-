package com.andreassamitsch.ilauncher.data.kodi

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class KodiSearchLauncher(private val context: Context) {
    fun isAvailable(): Boolean = searchIntent(query = "test").resolveActivity(context.packageManager) != null

    fun launch(query: String): Boolean {
        val intent = searchIntent(query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun searchIntent(query: String): Intent = Intent(Intent.ACTION_SEARCH).apply {
        component = ComponentName(KODI_PACKAGE, KODI_SEARCH_ACTIVITY)
        putExtra(SearchManager.QUERY, query)
    }

    private companion object {
        const val KODI_PACKAGE = "org.xbmc.kodi"
        const val KODI_SEARCH_ACTIVITY = "org.xbmc.kodi.XBMCSearchableActivity"
    }
}
