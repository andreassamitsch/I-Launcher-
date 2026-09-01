package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCollectionRefDto

/**
 * Compact, user-visible diagnostics for the experimental ServusTV catalogue path.
 *
 * Keep this deliberately free of URLs, content IDs and session data. The goal is to make API
 * shape mismatches visible on real hardware without leaking playback/session information.
 */
internal class ServusCatalogDiagnosticBuilder {
    private val entries = mutableListOf<String>()
    private var recordedCategories = 0
    private var omittedCategories = 0

    fun recordLanding(collections: List<ServusCollectionRefDto>) {
        val typeCounts = collections
            .groupingBy { ref -> ref.listType?.takeIf { it.isNotBlank() } ?: "<leer>" }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(", ") { (type, count) -> "$type=$count" }
            .ifBlank { "keine" }
        entries += "Landing: ${collections.size} Collections [$typeCounts]"
    }

    fun recordCategoryFilter(candidateCount: Int) {
        entries += "nach Filter: $candidateCount Kategorien"
    }

    fun recordCategory(title: String, cardCount: Int, recognizedShowCount: Int) {
        if (recordedCategories < MAX_RECORDED_CATEGORIES) {
            entries += "${compact(title)}: $cardCount Karten / $recognizedShowCount erkannte Sendungen"
            recordedCategories++
        } else {
            omittedCategories++
        }
    }

    fun recordUniqueShows(count: Int) {
        if (omittedCategories > 0) entries += "+$omittedCategories weitere Kategorien"
        entries += "eindeutige Sendungskarten: $count"
    }

    fun success(categoryCount: Int, showCount: Int): String {
        entries += "Ergebnis: $categoryCount Kategorien / $showCount Sendungen"
        return "Katalog OK · ${entries.joinToString(" · ")}"
    }

    fun failure(stage: String, detail: String): ServusCatalogRefreshException =
        ServusCatalogRefreshException(
            "Katalogfehler [$stage] · ${entries.joinToString(" · ").ifBlank { "noch keine API-Daten" }} · ${sanitize(detail)}",
        )

    fun failure(stage: String, throwable: Throwable): ServusCatalogRefreshException = failure(
        stage = stage,
        detail = buildString {
            append(throwable.javaClass.simpleName)
            throwable.message?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
        },
    )

    private fun compact(value: String): String = sanitize(value)
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_LABEL_CHARS)

    companion object {
        private const val MAX_RECORDED_CATEGORIES = 8
        private const val MAX_LABEL_CHARS = 42
        private val URL_PATTERN = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

        internal fun sanitize(value: String): String = value
            .replace(URL_PATTERN, "<URL>")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
            .take(MAX_DIAGNOSTIC_CHARS)

        private const val MAX_DIAGNOSTIC_CHARS = 1200
    }
}

internal class ServusCatalogRefreshException(
    message: String,
) : IllegalStateException(message)
