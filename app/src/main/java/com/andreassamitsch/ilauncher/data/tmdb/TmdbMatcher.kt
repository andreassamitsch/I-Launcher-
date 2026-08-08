package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import kotlin.math.abs
import kotlin.math.max

object TmdbMatcher {
    const val CONFIDENT_MATCH_THRESHOLD = 0.86f

    fun bestMatch(
        lookup: ParsedMediaLookup,
        candidates: List<TmdbCandidate>,
    ): TmdbMatch? {
        return candidates
            .asSequence()
            .map { candidate -> TmdbMatch(candidate, confidence(lookup, candidate)) }
            .filter { it.confidence >= CONFIDENT_MATCH_THRESHOLD }
            .sortedWith(
                compareByDescending<TmdbMatch> { it.confidence }
                    .thenByDescending { it.candidate.popularity },
            )
            .firstOrNull()
    }

    internal fun confidence(
        lookup: ParsedMediaLookup,
        candidate: TmdbCandidate,
    ): Float {
        val candidateTitles = listOfNotNull(candidate.title, candidate.originalTitle)
            .map(MediaTitleParser::normalizeTitle)
            .filter { it.isNotBlank() }

        val titleSimilarity = candidateTitles
            .maxOfOrNull { similarity(lookup.normalizedTitle, it) }
            ?: 0f

        val yearScore = when {
            lookup.releaseYear == null || candidate.releaseYear == null -> 0.07f
            lookup.releaseYear == candidate.releaseYear -> 0.14f
            abs(lookup.releaseYear - candidate.releaseYear) == 1 -> 0.10f
            else -> -0.10f
        }

        val typeScore = when {
            lookup.typeHint == MediaType.Unknown -> 0.04f
            lookup.typeHint == MediaType.Episode && candidate.type == MediaType.Series -> 0.08f
            lookup.typeHint == candidate.type -> 0.08f
            else -> -0.25f
        }

        return (titleSimilarity * 0.78f + yearScore + typeScore).coerceIn(0f, 1f)
    }

    internal fun similarity(left: String, right: String): Float {
        if (left == right && left.isNotBlank()) return 1f
        if (left.isBlank() || right.isBlank()) return 0f

        val distance = levenshteinDistance(left, right)
        return (1f - distance.toFloat() / max(left.length, right.length).toFloat())
            .coerceIn(0f, 1f)
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }
}
