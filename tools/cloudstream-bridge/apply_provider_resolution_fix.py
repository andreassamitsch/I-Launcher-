#!/usr/bin/env python3
from __future__ import annotations

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


def require_text(path: Path, needle: str, description: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise RuntimeError(f"Missing required {description} in {path}:\n{needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_provider_resolution_fix.py <cloudstream-source-root>")

    root = Path(sys.argv[1]).resolve()
    if not (root / "app/src/main").exists():
        raise RuntimeError(f"Not a CloudStream checkout: {root}")

    bridge = root / "app/src/main/java/com/lagradost/cloudstream3/ILauncherDirectPlay.kt"

    # APIRepository already enforces MainAPI.searchTimeoutMs/loadTimeoutMs with CloudStream's own
    # bounded defaults. The bridge used an additional 7-8 second outer timeout, which could cancel
    # a valid provider load long before CloudStream itself would. This is especially visible for
    # providers such as Moflix whose load() performs multiple sequential API calls. Remove the
    # duplicate shorter deadline and let APIRepository remain the single timeout authority.
    replace_once(
        bridge,
        '''                val loaded = withTimeoutOrNull(7_000) {
                    val url = api.getLoadUrl(SyncIdName.Imdb, imdbId) ?: return@withTimeoutOrNull null
                    (repo.load(url) as? Resource.Success)?.value
                }
''',
        '''                val url = api.getLoadUrl(SyncIdName.Imdb, imdbId)
                val loaded = url?.let { (repo.load(it) as? Resource.Success)?.value }
''',
    )

    replace_once(
        bridge,
        '''            val search = withTimeoutOrNull(8_000) {
                when (val result = repo.search(query, page)) {
                    is Resource.Success -> result.value
                    else -> null
                }
            } ?: break
''',
        '''            val search = when (val result = repo.search(query, page)) {
                is Resource.Success -> result.value
                else -> {
                    Log.i(TAG, "search failed provider=${repo.name} page=$page")
                    break
                }
            }
''',
    )

    replace_once(
        bridge,
        '''                val loaded = withTimeoutOrNull(8_000) {
                    when (val result = repo.load(candidate.url)) {
                        is Resource.Success -> result.value
                        else -> null
                    }
                } ?: continue
                if (loadedMatches(loaded, request, trustIdentity = false) && supportsExactRequest(loaded, request)) {
''',
        '''                val loaded = when (val result = repo.load(candidate.url)) {
                    is Resource.Success -> result.value
                    else -> {
                        Log.i(TAG, "load failed provider=${api.name}")
                        null
                    }
                } ?: continue
                if (loadedMatches(loaded, request, trustIdentity = false) && supportsExactRequest(loaded, request)) {
''',
    )

    # Moflix and several other providers expose external IDs only on LoadResponse. Those IDs are a
    # stronger identity signal than a provider's display title/year. Use an exact TMDB/IMDb match
    # as authoritative, reject explicit ID conflicts, and only fall back to title/year when the
    # provider supplies no comparable external identity.
    replace_once(
        bridge,
        "import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie\n",
        """import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
""",
    )

    replace_once(
        bridge,
        '''    private fun loadedMatches(response: LoadResponse, request: Request, trustIdentity: Boolean): Boolean {
        if (!typeMatches(response.type, request.kind)) return false
        if (request.year != null && response.year != null && abs(request.year - response.year!!) > 1) return false
        if (trustIdentity) return true
        val loadedTitle = normalizeTitleForMatch(response.name, request.year)
        return listOfNotNull(request.title, request.originalTitle)
            .map { normalizeTitleForMatch(it, request.year) }
            .any { it == loadedTitle }
    }
''',
        '''    internal enum class ExternalIdentityMatch { Exact, Conflict, Unknown }

    internal fun externalIdentityMatch(
        response: LoadResponse,
        request: Request,
    ): ExternalIdentityMatch {
        var exact = false

        request.tmdbId?.let { expected ->
            response.getTMDbId()?.toIntOrNull()?.let { actual ->
                if (actual != expected) return ExternalIdentityMatch.Conflict
                exact = true
            }
        }

        request.imdbId?.trim()?.takeIf(String::isNotBlank)?.let { expected ->
            response.getImdbId()?.trim()?.takeIf(String::isNotBlank)?.let { actual ->
                if (!actual.equals(expected, ignoreCase = true)) {
                    return ExternalIdentityMatch.Conflict
                }
                exact = true
            }
        }

        return if (exact) ExternalIdentityMatch.Exact else ExternalIdentityMatch.Unknown
    }

    internal fun loadedMatches(
        response: LoadResponse,
        request: Request,
        trustIdentity: Boolean,
    ): Boolean {
        if (!typeMatches(response.type, request.kind)) return false

        when (externalIdentityMatch(response, request)) {
            ExternalIdentityMatch.Exact -> return true
            ExternalIdentityMatch.Conflict -> return false
            ExternalIdentityMatch.Unknown -> Unit
        }

        // getLoadUrl(Imdb, id) is itself an explicit identity lookup. If the provider does not
        // repeat the ID on LoadResponse, do not downgrade that exact lookup because of title/year
        // decoration in the returned metadata.
        if (trustIdentity) return true

        if (request.year != null && response.year != null && abs(request.year - response.year!!) > 1) {
            return false
        }
        val loadedTitle = normalizeTitleForMatch(response.name, request.year)
        return listOfNotNull(request.title, request.originalTitle)
            .map { normalizeTitleForMatch(it, request.year) }
            .any { it == loadedTitle }
    }
''',
    )

    require_text(bridge, "val loaded = url?.let { (repo.load(it) as? Resource.Success)?.value }", "CloudStream-native sync load timeout")
    require_text(bridge, "val search = when (val result = repo.search(query, page))", "CloudStream-native search timeout")
    require_text(bridge, "val loaded = when (val result = repo.load(candidate.url))", "CloudStream-native candidate load timeout")
    require_text(bridge, "ExternalIdentityMatch.Exact -> return true", "external ID exact-match acceptance")
    require_text(bridge, "ExternalIdentityMatch.Conflict -> return false", "external ID conflict rejection")


if __name__ == "__main__":
    main()
