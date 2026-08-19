#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"Expected exactly one match in {path}: found {count}\n--- needle ---\n{old}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start_marker: str, end_marker: str, replacement: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(start_marker) != 1:
        raise RuntimeError(f"Expected one start marker in {path}: {start_marker}")
    start = text.index(start_marker)
    end = text.find(end_marker, start)
    if end < 0:
        raise RuntimeError(f"Missing end marker in {path}: {end_marker}")
    path.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


def require_text(path: Path, needle: str, description: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise RuntimeError(f"Missing required {description} in {path}:\n{needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_search_matching_fix.py <cloudstream-source-root>")

    root = Path(sys.argv[1]).resolve()
    if not (root / "app/src/main").exists():
        raise RuntimeError(f"Not a CloudStream checkout: {root}")

    bridge = root / "app/src/main/java/com/lagradost/cloudstream3/ILauncherDirectPlay.kt"

    # CloudStream's normal search probes active providers without treating supportedTypes as an
    # authoritative search gate. Some extensions also mislabel SearchResponse.type (Moflix, for
    # example, emits TvSeries search cards even for movies) and only expose the real type from
    # load(). Mirror that behavior here: provider/search-card type metadata may influence ordering,
    # but only the strict LoadResponse verification is allowed to reject the media type.
    replace_once(
        bridge,
        '''        if (!supportsRequestedKind(api, request.kind)) {
            Log.i(TAG, "skip provider=${api.name} reason=type")
            return null
        }
        val repo = APIRepository(api)
''',
        '''        if (!supportsRequestedKind(api, request.kind)) {
            Log.i(TAG, "probe provider=${api.name} despite declared type mismatch")
        }
        val repo = APIRepository(api)
''',
    )

    # Some extensions implement search as a broad OR across all words. Keep the strict
    # LoadResponse identity check, but improve discovery before that check: rank weak hits,
    # inspect more pages only while no exact/decorated candidate is visible, and retry with a
    # compact high-signal title query when the provider's full-title search is too broad.
    replace_between(
        bridge,
        "        val titles = listOfNotNull(request.title, request.originalTitle)\n",
        "            // Do not require the provider's SearchResponse display label to exactly equal TMDB's\n",
        """        for (query in buildSearchQueries(request)) {\n            val candidates = searchCandidates(repo, query, request)\n\n""",
    )

    replace_between(
        bridge,
        "    internal fun normalizeTitle(value: String): String =",
        "    private fun normalizeWhitespace(value: String): String =",
        r'''    private suspend fun searchCandidates(
        repo: APIRepository,
        query: String,
        request: Request,
    ): List<SearchResponse> {
        val collected = mutableListOf<SearchResponse>()
        var page = 1
        var continuePaging = true

        while (continuePaging && page <= MAX_SEARCH_PAGES) {
            val search = withTimeoutOrNull(8_000) {
                when (val result = repo.search(query, page)) {
                    is Resource.Success -> result.value
                    else -> null
                }
            } ?: break

            collected += search.items
            val bestRank = collected.asSequence()
                .map { candidate -> searchCandidateRank(candidate.name, request) }
                .minOrNull()
            continuePaging = shouldFetchNextSearchPage(search.hasNext, page, bestRank)
            page += 1
        }

        return collected
            .distinctBy { it.url }
            .sortedBy { candidate -> searchCandidateSortKey(candidate.name, candidate.type, request) }
    }

    internal fun buildSearchQueries(request: Request): List<String> = buildList {
        listOfNotNull(request.title, request.originalTitle).forEach { rawTitle ->
            val full = normalizeWhitespace(rawTitle)
            if (full.isBlank()) return@forEach
            add(full)

            val compact = compactSearchQuery(full)
            if (normalizeTitle(compact) != normalizeTitle(full)) add(compact)
        }
    }.distinctBy(::normalizeTitle)

    internal fun compactSearchQuery(value: String): String {
        val normalized = normalizeTitle(value)
        val meaningful = normalized.split(' ')
            .filter(String::isNotBlank)
            .filter { token ->
                token.length > 2 &&
                    token !in SEARCH_STOP_WORDS &&
                    !YEAR_TOKEN.matches(token)
            }
        return if (meaningful.size >= 2) meaningful.joinToString(" ") else normalizeWhitespace(value)
    }

    internal fun shouldFetchNextSearchPage(hasNext: Boolean, page: Int, bestRank: Int?): Boolean =
        hasNext && page < MAX_SEARCH_PAGES && (bestRank == null || bestRank > SAFE_SEARCH_RANK)

    internal fun normalizeTitle(value: String): String = normalizeWhitespace(value)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    internal fun normalizeTitleForMatch(value: String, year: Int?): String {
        val compact = normalizeWhitespace(value)
        TRAILING_DECORATED_YEAR.find(compact)?.let { match ->
            val decoratedYear = match.groupValues.getOrNull(1)?.toIntOrNull()
            if (year == null || decoratedYear == year) {
                val withoutDecoration = compact.removeRange(match.range).trim()
                if (withoutDecoration.isNotBlank()) return normalizeTitle(withoutDecoration)
            }
        }

        val normalized = normalizeTitle(compact)
        val suffix = year?.let { " $it" } ?: return normalized
        return normalized.removeSuffix(suffix).trim()
    }

    internal fun searchCandidateRank(candidateName: String, request: Request): Int {
        val exactCandidate = normalizeTitle(candidateName)
        val matchCandidate = normalizeTitleForMatch(candidateName, request.year)
        val exactTitles = listOfNotNull(request.title, request.originalTitle)
            .map(::normalizeTitle)
            .filter(String::isNotBlank)
        val matchTitles = listOfNotNull(request.title, request.originalTitle)
            .map { normalizeTitleForMatch(it, request.year) }
            .filter(String::isNotBlank)
        return when {
            exactTitles.any { it == exactCandidate } -> 0
            matchTitles.any { it == matchCandidate } -> 1
            matchTitles.isEmpty() -> Int.MAX_VALUE
            else -> matchTitles.minOf { title -> fuzzyTitleRank(matchCandidate, title) }
        }
    }

    internal fun searchCandidateSortKey(
        candidateName: String,
        candidateType: TvType?,
        request: Request,
    ): Long {
        val titleRank = searchCandidateRank(candidateName, request).toLong()
        val typeTieBreak = if (typeMatches(candidateType, request.kind)) 0L else 1L
        return titleRank * SEARCH_TYPE_TIE_BREAK_SCALE + typeTieBreak
    }

    private fun fuzzyTitleRank(candidate: String, target: String): Int {
        if (candidate.isBlank() || target.isBlank()) return Int.MAX_VALUE
        val candidateSet = candidate.split(' ').filter(String::isNotBlank).toSet()
        val targetSet = target.split(' ').filter(String::isNotBlank).toSet()
        val missing = targetSet.count { it !in candidateSet }
        val extra = candidateSet.count { it !in targetSet }
        val phrasePenalty = if (candidate.contains(target) || target.contains(candidate)) 0 else 25
        val lengthPenalty = abs(candidate.length - target.length).coerceAtMost(99)
        return 100 + missing * 200 + extra * 20 + phrasePenalty + lengthPenalty
    }

''',
    )

    replace_once(
        bridge,
        "    private const val MAX_SEARCH_CANDIDATES_PER_QUERY = 6\n}",
        r'''    private val TRAILING_DECORATED_YEAR = Regex("""\s*[\(\[]((?:19|20|21)\d{2})[\)\]]\s*$""")
    private val YEAR_TOKEN = Regex("""(?:19|20|21)\d{2}""")
    private val SEARCH_STOP_WORDS = setOf(
        "der", "die", "das", "den", "dem", "des",
        "ein", "eine", "einer", "eines", "und",
        "the", "a", "an", "of", "and",
        "film", "movie",
    )
    private const val SAFE_SEARCH_RANK = 1
    private const val MAX_SEARCH_PAGES = 3
    private const val MAX_SEARCH_CANDIDATES_PER_QUERY = 8
    private const val SEARCH_TYPE_TIE_BREAK_SCALE = 2L
}''',
    )

    test_source = Path(__file__).with_name("ILauncherBridgeSearchMatchingTest.kt")
    test_target = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherBridgeSearchMatchingTest.kt"
    test_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(test_source, test_target)

    require_text(bridge, "probe provider=${api.name} despite declared type mismatch", "non-authoritative provider type metadata")
    require_text(bridge, "buildSearchQueries(request)", "broad-search query fallback")
    require_text(bridge, "searchCandidates(repo, query, request)", "ranked paged provider search")
    require_text(bridge, "searchCandidateSortKey(candidate.name, candidate.type, request)", "non-authoritative SearchResponse type ranking")
    require_text(bridge, "TRAILING_DECORATED_YEAR", "provider year-decoration matching")
    require_text(bridge, "MAX_SEARCH_PAGES = 3", "bounded provider pagination")


if __name__ == "__main__":
    main()
