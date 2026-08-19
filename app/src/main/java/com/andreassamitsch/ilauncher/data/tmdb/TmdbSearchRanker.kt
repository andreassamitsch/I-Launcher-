package com.andreassamitsch.ilauncher.data.tmdb

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Keeps TMDB search useful for a title-first TV launcher without replacing TMDB's search semantics.
 *
 * TMDB already returns relevant candidates. We stabilize the first page with a blended score:
 * title relevance remains the strongest signal, while vote count and popularity can lift a clearly
 * better-known result above an obscure exact-title collision. This is especially important for
 * partial TV-remote queries such as "expend", where "The Expendables 4" should not be buried just
 * because an unrelated niche title happens to be named exactly "Expend".
 *
 * Original TMDB order remains the final tie-breaker.
 */
internal object TmdbSearchRanker {
    fun rank(query: String, results: List<TmdbSearchResultDto>): List<TmdbSearchResultDto> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank() || results.size < 2) return results

        return results
            .mapIndexed { index, item ->
                val titleScore = titleMatchScore(normalizedQuery, item)
                RankedResult(
                    item = item,
                    titleMatchScore = titleScore,
                    totalScore = titleScore + establishedScore(item),
                    sourceOrder = index,
                )
            }
            .sortedWith(
                compareByDescending<RankedResult> { it.totalScore }
                    .thenByDescending { it.titleMatchScore }
                    .thenByDescending { it.item.voteCount }
                    .thenByDescending { it.item.popularity }
                    .thenByDescending { it.item.voteAverage }
                    .thenBy { it.sourceOrder },
            )
            .map(RankedResult::item)
    }

    internal fun titleMatchScore(query: String, item: TmdbSearchResultDto): Int {
        val candidates = listOfNotNull(
            item.title,
            item.originalTitle,
            item.name,
            item.originalName,
        ).map(::normalize).filter(String::isNotBlank).distinct()
        if (candidates.isEmpty()) return MATCH_OTHER

        if (candidates.any { it == query }) return MATCH_EXACT
        if (candidates.any { it.startsWith(query) }) return MATCH_PREFIX
        if (candidates.any { candidate -> candidate.split(' ').any { it.startsWith(query) } }) {
            return MATCH_WORD_PREFIX
        }
        if (candidates.any { it.contains(query) }) return MATCH_CONTAINS

        val queryTokens = query.split(' ').filter(String::isNotBlank)
        if (queryTokens.size > 1 && candidates.any { candidate -> queryTokens.all(candidate::contains) }) {
            return MATCH_TOKENS
        }
        return MATCH_OTHER
    }

    private fun establishedScore(item: TmdbSearchResultDto): Int {
        val voteCountScore = (ln(item.voteCount.coerceAtLeast(0).toDouble() + 1.0) * VOTE_COUNT_WEIGHT)
            .roundToInt()
            .coerceAtMost(MAX_VOTE_COUNT_SCORE)
        val popularityScore = (ln(item.popularity.coerceAtLeast(0.0) + 1.0) * POPULARITY_WEIGHT)
            .roundToInt()
            .coerceAtMost(MAX_POPULARITY_SCORE)
        return voteCountScore + popularityScore
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
        val titleMatchScore: Int,
        val totalScore: Int,
        val sourceOrder: Int,
    )

    private const val MATCH_OTHER = 0
    private const val MATCH_TOKENS = 680
    private const val MATCH_CONTAINS = 760
    private const val MATCH_WORD_PREFIX = 900
    private const val MATCH_PREFIX = 920
    private const val MATCH_EXACT = 1_000

    private const val VOTE_COUNT_WEIGHT = 34.0
    private const val POPULARITY_WEIGHT = 22.0
    private const val MAX_VOTE_COUNT_SCORE = 360
    private const val MAX_POPULARITY_SCORE = 140

    private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
}
