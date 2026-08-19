package com.andreassamitsch.ilauncher.data.tmdb

import java.text.Normalizer
import java.util.Locale

/**
 * Keeps TMDB search useful for a title-first TV launcher without replacing TMDB's search semantics.
 *
 * TMDB already returns relevant candidates. We only stabilize the first page so exact localized or
 * original-title matches win, then prefer well-established results over obscure spin-offs that happen
 * to share the same prefix. Original TMDB order remains the final tie-breaker.
 */
internal object TmdbSearchRanker {
    fun rank(query: String, results: List<TmdbSearchResultDto>): List<TmdbSearchResultDto> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank() || results.size < 2) return results

        return results
            .mapIndexed { index, item ->
                RankedResult(
                    item = item,
                    titleMatchTier = titleMatchTier(normalizedQuery, item),
                    sourceOrder = index,
                )
            }
            .sortedWith(
                compareByDescending<RankedResult> { it.titleMatchTier }
                    .thenByDescending { it.item.voteCount }
                    .thenByDescending { it.item.popularity }
                    .thenByDescending { it.item.voteAverage }
                    .thenBy { it.sourceOrder },
            )
            .map(RankedResult::item)
    }

    internal fun titleMatchTier(query: String, item: TmdbSearchResultDto): Int {
        val candidates = listOfNotNull(
            item.title,
            item.originalTitle,
            item.name,
            item.originalName,
        ).map(::normalize).filter(String::isNotBlank).distinct()
        if (candidates.isEmpty()) return MATCH_OTHER

        if (candidates.any { it == query }) return MATCH_EXACT
        if (candidates.any { it.startsWith(query) }) return MATCH_PREFIX
        if (candidates.any { it.contains(query) }) return MATCH_CONTAINS

        val queryTokens = query.split(' ').filter(String::isNotBlank)
        if (queryTokens.size > 1 && candidates.any { candidate -> queryTokens.all(candidate::contains) }) {
            return MATCH_TOKENS
        }
        return MATCH_OTHER
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS_REGEX, "")
        .lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC_REGEX, " ")
        .trim()
        .replace(MULTI_SPACE_REGEX, " ")

    private data class RankedResult(
        val item: TmdbSearchResultDto,
        val titleMatchTier: Int,
        val sourceOrder: Int,
    )

    private const val MATCH_OTHER = 0
    private const val MATCH_TOKENS = 1
    private const val MATCH_CONTAINS = 2
    private const val MATCH_PREFIX = 3
    private const val MATCH_EXACT = 4

    private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
}
