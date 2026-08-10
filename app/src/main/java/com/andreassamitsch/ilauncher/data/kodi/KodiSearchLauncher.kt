package com.andreassamitsch.ilauncher.data.kodi

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlin.concurrent.thread

class KodiSearchLauncher(private val context: Context) {
    fun isAvailable(): Boolean = context.packageManager.getLaunchIntentForPackage(KODI_PACKAGE) != null

    fun launch(query: String): Boolean {
        if (!isAvailable()) return false

        // Kodi's exported ACTION_SEARCH activity currently queries a media/search URI that its own
        // exported media provider does not register. Use the provider's supported suggestions route
        // instead, then feed the selected Kodi-owned videodb URI back through the activity's working
        // ACTION_GET_CONTENT path. Provider IPC can involve Kodi JSON-RPC, so never block the UI.
        thread(name = "i-launcher-kodi-search") {
            val suggestion = runCatching { findSuggestion(query) }.getOrNull()
            if (suggestion != null && launchSuggestion(suggestion)) return@thread
            launchKodiHome()
        }
        return true
    }

    private fun findSuggestion(query: String): KodiSuggestion? {
        val uri = Uri.Builder()
            .scheme("content")
            .authority(KODI_MEDIA_AUTHORITY)
            .appendPath(KODI_SUGGESTIONS_PATH)
            .appendPath(SearchManager.SUGGEST_URI_PATH_QUERY)
            .appendPath(query)
            .appendQueryParameter("limit", KODI_SUGGESTION_LIMIT.toString())
            .build()

        val suggestions = mutableListOf<KodiSuggestion>()
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val titleIndex = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1)
            val actionIndex = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_ACTION)
            val dataIndex = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA)
            if (titleIndex < 0 || dataIndex < 0) return@use

            while (cursor.moveToNext()) {
                val title = cursor.getString(titleIndex).orEmpty()
                val data = cursor.getString(dataIndex).orEmpty()
                if (title.isBlank() || data.isBlank()) continue
                suggestions += KodiSuggestion(
                    title = title,
                    action = if (actionIndex >= 0) cursor.getString(actionIndex) else null,
                    data = data,
                )
            }
        }
        return selectKodiSuggestion(query, suggestions)
    }

    private fun launchSuggestion(suggestion: KodiSuggestion): Boolean {
        val data = runCatching { Uri.parse(suggestion.data) }.getOrNull() ?: return false
        val intent = Intent(suggestion.action ?: Intent.ACTION_GET_CONTENT).apply {
            component = ComponentName(KODI_PACKAGE, KODI_SEARCH_ACTIVITY)
            this.data = data
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun launchKodiHome() {
        val intent = context.packageManager.getLaunchIntentForPackage(KODI_PACKAGE) ?: return
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private companion object {
        const val KODI_PACKAGE = "org.xbmc.kodi"
        const val KODI_SEARCH_ACTIVITY = "org.xbmc.kodi.XBMCSearchableActivity"
        const val KODI_MEDIA_AUTHORITY = "org.xbmc.kodi.media"
        const val KODI_SUGGESTIONS_PATH = "suggestions"
        const val KODI_SUGGESTION_LIMIT = 12
    }
}

internal data class KodiSuggestion(
    val title: String,
    val action: String?,
    val data: String,
)

internal fun selectKodiSuggestion(
    query: String,
    suggestions: List<KodiSuggestion>,
): KodiSuggestion? {
    val normalizedQuery = normalizeKodiTitle(query)
    if (normalizedQuery.isBlank()) return null

    return suggestions
        .map { suggestion ->
            val title = normalizeKodiTitle(suggestion.title)
            val score = when {
                title == normalizedQuery -> 3
                title.startsWith("$normalizedQuery ") || normalizedQuery.startsWith("$title ") -> 2
                title.contains(normalizedQuery) || normalizedQuery.contains(title) -> 1
                else -> 0
            }
            suggestion to score
        }
        .filter { (_, score) -> score >= 2 }
        .maxByOrNull { (_, score) -> score }
        ?.first
}

internal fun normalizeKodiTitle(value: String): String = value
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
