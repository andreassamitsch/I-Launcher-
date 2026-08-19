#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


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
        raise SystemExit("usage: apply_provider_interop_fix.py <cloudstream-source-root>")

    root = Path(sys.argv[1]).resolve()
    if not (root / "app/src/main").exists():
        raise RuntimeError(f"Not a CloudStream checkout: {root}")

    bridge = root / "app/src/main/java/com/lagradost/cloudstream3/ILauncherDirectPlay.kt"

    # Some real CloudStream extensions model a movie as TvSeriesLoadResponse(type = Movie)
    # and put the playable payload into the first Episode. CloudStream's ResultViewModel2 supports
    # this intentionally: it builds the episode list and, because response.isMovie() is true,
    # uses getMovie() -> the first ResultEpisode for playback. Mirror that exact interoperability
    # contract instead of requiring every provider to return MovieLoadResponse.
    replace_between(
        bridge,
        "    private fun supportsExactRequest(response: LoadResponse, request: Request): Boolean = when {\n",
        "    private fun supportsRequestedKind(api: MainAPI, kind: MediaKind): Boolean = when (kind) {\n",
        '''    private fun supportsExactRequest(response: LoadResponse, request: Request): Boolean = when {
        request.kind == MediaKind.Movie -> moviePlaybackData(response) != null
        request.kind == MediaKind.Episode && request.season != null && request.episode != null ->
            findEpisodeIndex(response, request.season, request.episode) >= 0
        else -> true
    }

    internal fun moviePlaybackData(response: LoadResponse): String? = when (response) {
        is MovieLoadResponse -> response.dataUrl.takeIf(String::isNotBlank)
        is TvSeriesLoadResponse -> if (response.isMovie()) {
            response.episodes
                .sortedBy { episode ->
                    (episode.season?.times(10_000) ?: 0) + (episode.episode ?: 0)
                }
                .firstOrNull()
                ?.data
                ?.takeIf(String::isNotBlank)
        } else {
            null
        }
        else -> null
    }

''',
    )

    replace_between(
        bridge,
        "    private fun prepareMovie(response: LoadResponse): PreparedPlayback? {\n",
        "    private fun prepareEpisode(\n",
        '''    private fun prepareMovie(response: LoadResponse): PreparedPlayback? {
        val parentId = response.getId()
        val result = when (response) {
            is MovieLoadResponse -> {
                if (response.dataUrl.isBlank()) return null
                buildResultEpisode(
                    headerName = response.name,
                    name = response.name,
                    poster = response.posterUrl,
                    episode = 0,
                    season = null,
                    data = response.dataUrl,
                    apiName = response.apiName,
                    id = parentId,
                    index = 0,
                    tvType = response.type,
                    parentId = parentId,
                )
            }

            is TvSeriesLoadResponse -> {
                if (!response.isMovie()) return null
                val episode = response.episodes
                    .sortedBy { item ->
                        (item.season?.times(10_000) ?: 0) + (item.episode ?: 0)
                    }
                    .firstOrNull() ?: return null
                if (episode.data.isBlank()) return null

                val episodeIndex = episode.episode ?: 1
                val seasonData = episode.season?.let { season ->
                    response.seasonNames?.firstOrNull { it.season == season }
                }
                val displaySeason = seasonData?.displaySeason ?: episode.season
                val id = parentId + (episode.season?.times(100_000) ?: 0) + episodeIndex + 1

                buildResultEpisode(
                    headerName = response.name,
                    name = episode.name,
                    poster = episode.posterUrl ?: response.posterUrl,
                    episode = episodeIndex,
                    seasonIndex = episode.season,
                    season = displaySeason,
                    data = episode.data,
                    apiName = response.apiName,
                    id = id,
                    index = 0,
                    rating = episode.score,
                    description = episode.description,
                    tvType = response.type,
                    parentId = parentId,
                    airDate = episode.date,
                    runTime = episode.runTime,
                    seasonData = seasonData,
                )
            }

            else -> return null
        }

        return PreparedPlayback(
            generator = RepoLinkGenerator(listOf(result), response),
            index = 0,
            syncData = HashMap(response.syncData),
            providerName = response.apiName,
        )
    }

''',
    )

    require_text(bridge, "request.kind == MediaKind.Movie -> moviePlaybackData(response) != null", "movie response interoperability gate")
    require_text(bridge, "is TvSeriesLoadResponse -> if (response.isMovie())", "movie-like series response data extraction")
    require_text(bridge, "RepoLinkGenerator(listOf(result), response)", "movie-like series playback generator")


if __name__ == "__main__":
    main()
