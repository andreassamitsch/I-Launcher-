package com.lagradost.cloudstream3

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * I Launcher -> CloudStream direct-play bridge.
 *
 * This intentionally stays inside the CloudStream process: installed extensions, MainAPI,
 * APIRepository and RepoLinkGenerator remain the only code resolving provider pages and links.
 */
object ILauncherDirectPlay {
    const val SCHEME = "cloudstreamplay"
    const val API_VERSION = "v1"

    internal enum class MediaKind { Movie, Series, Episode, Unknown }

    internal data class Request(
        val title: String,
        val originalTitle: String?,
        val year: Int?,
        val kind: MediaKind,
        val season: Int?,
        val episode: Int?,
        val imdbId: String?,
    )

    private data class Match(
        val response: LoadResponse,
        val fromSyncId: Boolean,
    )

    fun handle(activity: FragmentActivity, rawUri: String): Boolean {
        val request = parseRequest(rawUri) ?: return false
        ioSafe {
            try {
                val providers = awaitProviders(activity)
                val match = resolve(providers, request)
                if (match == null) {
                    fallbackToSearch(activity, request.title)
                    return@ioSafe
                }

                when {
                    request.kind == MediaKind.Movie || match.response.isMovie() -> {
                        if (!playMovie(activity, match.response)) {
                            openResolvedDetails(activity, match.response)
                        }
                    }
                    request.kind == MediaKind.Episode && request.season != null && request.episode != null -> {
                        if (!playEpisode(activity, match.response, request.season, request.episode)) {
                            openResolvedDetails(activity, match.response)
                        }
                    }
                    else -> {
                        // A series without an episode identity cannot be played safely. We still skip the
                        // CloudStream search/results page and open the already resolved provider detail.
                        openResolvedDetails(activity, match.response)
                    }
                }
            } catch (t: Throwable) {
                logError(t)
                fallbackToSearch(activity, request.title)
            }
        }
        return true
    }

    internal fun parseRequest(rawUri: String): Request? {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.host != API_VERSION) return null
        val title = uri.getQueryParameter("title")?.let(::normalizeWhitespace).orEmpty()
        if (title.isBlank()) return null
        val type = when (uri.getQueryParameter("type")?.lowercase()) {
            "movie" -> MediaKind.Movie
            "series" -> MediaKind.Series
            "episode" -> MediaKind.Episode
            else -> MediaKind.Unknown
        }
        return Request(
            title = title,
            originalTitle = uri.getQueryParameter("originalTitle")
                ?.let(::normalizeWhitespace)
                ?.takeIf(String::isNotBlank),
            year = uri.getQueryParameter("year")?.toIntOrNull(),
            kind = type,
            season = uri.getQueryParameter("season")?.toIntOrNull(),
            episode = uri.getQueryParameter("episode")?.toIntOrNull(),
            imdbId = uri.getQueryParameter("imdbId")?.trim()?.takeIf(String::isNotBlank),
        )
    }

    private suspend fun awaitProviders(activity: FragmentActivity): List<MainAPI> {
        repeat(48) {
            val activeNames = activity.getApiSettings()
            val current = apis.withLock {
                apis.filter { api ->
                    activeNames.contains(api.name)
                }
            }
            if (current.isNotEmpty()) return current
            delay(250)
        }
        return emptyList()
    }

    private suspend fun resolve(providers: List<MainAPI>, request: Request): Match? {
        if (providers.isEmpty()) return null

        request.imdbId?.let { imdbId ->
            providers.asSequence()
                .filter { SyncIdName.Imdb in it.supportedSyncNames }
                .filter { supportsRequestedKind(it, request.kind) }
                .forEach { api ->
                    val loaded = withTimeoutOrNull(8_000) {
                        val url = api.getLoadUrl(SyncIdName.Imdb, imdbId) ?: return@withTimeoutOrNull null
                        val result = APIRepository(api).load(url)
                        (result as? Resource.Success)?.value
                    }
                    if (loaded != null && loadedMatches(loaded, request, trustIdentity = true)) {
                        return Match(loaded, fromSyncId = true)
                    }
                }
        }

        val titles = listOfNotNull(request.title, request.originalTitle)
            .map(::normalizeWhitespace)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeTitle)
        val semaphore = Semaphore(4)
        val candidates = coroutineScope {
            providers
                .filter { supportsRequestedKind(it, request.kind) }
                .map { api ->
                    async {
                        semaphore.withPermit {
                            titles.flatMap { query ->
                                withTimeoutOrNull(10_000) {
                                    when (val search = APIRepository(api).search(query, 1)) {
                                        is Resource.Success -> search.value.items
                                        else -> emptyList()
                                    }
                                }.orEmpty()
                            }.filter { candidate -> searchCandidateMatches(candidate, request) }
                        }
                    }
                }.awaitAll().flatten()
        }.distinctBy { "${it.apiName}|${it.url}" }

        for (candidate in candidates.take(12)) {
            val api = providers.firstOrNull { it.name == candidate.apiName } ?: continue
            val loaded = withTimeoutOrNull(10_000) {
                when (val result = APIRepository(api).load(candidate.url)) {
                    is Resource.Success -> result.value
                    else -> null
                }
            } ?: continue
            if (loadedMatches(loaded, request, trustIdentity = false)) {
                return Match(loaded, fromSyncId = false)
            }
        }
        return null
    }

    internal fun normalizeTitle(value: String): String = normalizeWhitespace(value)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun normalizeWhitespace(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")

    private fun searchCandidateMatches(candidate: SearchResponse, request: Request): Boolean {
        if (!typeMatches(candidate.type, request.kind)) return false
        val candidateTitle = normalizeTitle(candidate.name)
        val acceptedTitles = listOfNotNull(request.title, request.originalTitle).map(::normalizeTitle)
        return candidateTitle.isNotBlank() && acceptedTitles.any { it == candidateTitle }
    }

    private fun loadedMatches(response: LoadResponse, request: Request, trustIdentity: Boolean): Boolean {
        if (!typeMatches(response.type, request.kind)) return false
        if (request.year != null && response.year != null && abs(request.year - response.year!!) > 1) return false
        if (trustIdentity) return true
        val loadedTitle = normalizeTitle(response.name)
        return listOfNotNull(request.title, request.originalTitle)
            .map(::normalizeTitle)
            .any { it == loadedTitle }
    }

    private fun supportsRequestedKind(api: MainAPI, kind: MediaKind): Boolean = when (kind) {
        MediaKind.Movie -> api.supportedTypes.any { it.isMovieType() }
        MediaKind.Series, MediaKind.Episode -> api.supportedTypes.any { it.isEpisodeBased() }
        MediaKind.Unknown -> true
    }

    private fun typeMatches(type: TvType?, kind: MediaKind): Boolean = when (kind) {
        MediaKind.Movie -> type?.isMovieType() != false
        MediaKind.Series, MediaKind.Episode -> type?.isEpisodeBased() != false
        MediaKind.Unknown -> true
    }

    private fun playMovie(activity: FragmentActivity, response: LoadResponse): Boolean {
        val movie = response as? MovieLoadResponse ?: return false
        if (movie.dataUrl.isBlank()) return false
        val parentId = response.getId()
        val result = buildResultEpisode(
            headerName = response.name,
            name = response.name,
            poster = response.posterUrl,
            episode = 0,
            season = null,
            data = movie.dataUrl,
            apiName = response.apiName,
            id = parentId,
            index = 0,
            tvType = response.type,
            parentId = parentId,
        )
        val generator = RepoLinkGenerator(listOf(result), response)
        main {
            activity.navigate(
                R.id.global_to_navigation_player,
                GeneratorPlayer.newInstance(generator, 0, HashMap(response.syncData)),
            )
        }
        return true
    }

    private fun playEpisode(
        activity: FragmentActivity,
        response: LoadResponse,
        requestedSeason: Int,
        requestedEpisode: Int,
    ): Boolean {
        val series = response as? TvSeriesLoadResponse ?: return false
        val displaySeasons = series.seasonNames?.associate { it.season to it.displaySeason }.orEmpty()
        val selectedIndex = series.episodes.indexOfFirst { ep ->
            val displaySeason = ep.season?.let { displaySeasons[it] ?: it }
            displaySeason == requestedSeason && ep.episode == requestedEpisode
        }
        if (selectedIndex < 0) return false
        val parentId = response.getId()
        val results = series.episodes.mapIndexed { index, ep ->
            val displaySeason = ep.season?.let { displaySeasons[it] ?: it }
            buildResultEpisode(
                headerName = response.name,
                name = ep.name,
                poster = ep.posterUrl ?: response.posterUrl,
                episode = ep.episode ?: (index + 1),
                seasonIndex = ep.season,
                season = displaySeason,
                data = ep.data,
                apiName = response.apiName,
                id = episodeId(response.apiName, ep.data, parentId, index),
                index = index,
                rating = ep.score,
                description = ep.description,
                tvType = response.type,
                parentId = parentId,
                totalEpisodeIndex = displaySeason?.let { season ->
                    ep.episode?.let { episode -> series.getTotalEpisodeIndex(episode, season) }
                },
                airDate = ep.date,
                runTime = ep.runTime,
                seasonData = ep.season?.let { season -> series.seasonNames?.firstOrNull { it.season == season } },
            )
        }
        val generator = RepoLinkGenerator(results, response)
        main {
            activity.navigate(
                R.id.global_to_navigation_player,
                GeneratorPlayer.newInstance(generator, selectedIndex, HashMap(response.syncData)),
            )
        }
        return true
    }

    private fun episodeId(apiName: String, data: String, parentId: Int, index: Int): Int =
        "$apiName|$parentId|$index|$data".hashCode()

    private fun openResolvedDetails(activity: FragmentActivity, response: LoadResponse) {
        main {
            activity.loadResult(response.url, response.apiName, response.name)
        }
    }

    private fun fallbackToSearch(activity: FragmentActivity, title: String) {
        main {
            MainActivity.handleAppIntentUrl(
                activity,
                "$SCHEME_SEARCH://${Uri.encode(title)}",
                isWebview = false,
            )
        }
    }

    private const val SCHEME_SEARCH = "cloudstreamsearch"
}
