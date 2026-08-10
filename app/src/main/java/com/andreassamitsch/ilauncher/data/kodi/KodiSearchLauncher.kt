package com.andreassamitsch.ilauncher.data.kodi

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

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
        // Kodi's exported XBMCSearchableActivity logs intent.data before it handles ACTION_SEARCH.
        // A non-null, inert URI avoids the null-data crash while SearchManager.QUERY remains
        // the only value Kodi uses for its actual media-library lookup.
        data = KODI_SEARCH_DATA_URI
        putExtra(SearchManager.QUERY, query)
    }

    private companion object {
        const val KODI_PACKAGE = "org.xbmc.kodi"
        const val KODI_SEARCH_ACTIVITY = "org.xbmc.kodi.XBMCSearchableActivity"
        val KODI_SEARCH_DATA_URI: Uri = Uri.parse("kodi-search://query")
    }
}
