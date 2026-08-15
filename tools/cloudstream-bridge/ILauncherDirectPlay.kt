package com.lagradost.cloudstream3

import android.content.Context
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP
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
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

/**
 * I Launcher -> CloudStream direct-play bridge.
 *
 * Installed extensions, MainAPI, APIRepository and RepoLinkGenerator remain inside CloudStream.
 * Provider preference and the last successful provider are also stored here because provider names
 * are CloudStream-owned runtime data and must not be duplicated into I Launcher.
 */
object ILauncherDirectPlay {
    const val SCHEME = "cloudstreamplay"
    const val API_VERSION = "v1"

    internal enum class MediaKind { Movie, Series, Episode, Unknown }
    internal enum class ProviderSelection { Automatic, Choose }

    internal data class Request(
        val title: String,
        val originalTitle: String?,
        val year: Int?,
        val kind: MediaKind,
        val season: Int?,
        val episode: Int?,
        val tmdbId: Int?,
        val tmdbEpisodeId: Int?,
        val imdbId: String?,
        val providerSelection: ProviderSelection,
    )

    private data class Match(val response: LoadResponse)
    private data class PreparedPlayback(
        val generator: RepoLinkGenerator,
        val index: Int,
        val syncData: HashMap<String, String>,
        val providerName: String,
    )

    fun handle(activity: FragmentActivity, rawUri: String): Boolean {
        val request = parseRequest(rawUri) ?: return false
        ioSafe {
            try {
                val providers = awaitProviders(activity)
                val orderedProviders = orderProviders(activity, providers, request)
                if (request.providerSelection == ProviderSelection.Choose) {
                    val matches = resolveAll(orderedProviders, request)
                    main {
                        if (matches.isEmpty()) {
                            showNoDirectMatchDialog(activity, request, providers)
                        } else {
                            showProviderChooser(activity, request, providers, matches)
                        }
                    }
                    return@ioSafe
                }

                if (!resolveAndExecuteAutomatic(activity, orderedProviders, request)) {
                    fallbackToSearch(activity, request.title)
                }
            } catch (t: Throwable) {
                logError(t)
                fallbackToSearch(activity, request.title)
            }
        }
        return true
    }

    internal fun parseRequest(rawUri: String): Request? {
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.host != API_VERSION) return null
        val query = parseQuery(uri.rawQuery)
        val title = query["title"]?.let(::normalizeWhitespace).orEmpty()
        if (title.isBlank()) return null
        val type = when (query["type"]?.lowercase()) {
            "movie" -> MediaKind.Movie
            "series" -> MediaKind.Series
            "episode" -> MediaKind.Episode
            else -> MediaKind.Unknown
        }
        return Request(
            title = title,
            originalTitle = query["originalTitle"]
                ?.let(::normalizeWhitespace)
                ?.takeIf(String::isNotBlank),
            year = query["year"]?.toIntOrNull(),
            kind = type,
            season = query["season"]?.toIntOrNull(),
            episode = query["episode"]?.toIntOrNull(),
            tmdbId = query["tmdbId"]?.toIntOrNull(),
            tmdbEpisodeId = query["tmdbEpisodeId"]?.toIntOrNull(),
            imdbId = query["imdbId"]?.trim()?.takeIf(String::isNotBlank),
            providerSelection = when (query["selection"]?.lowercase()) {
                "choose" -> ProviderSelection.Choose
                else -> ProviderSelection.Automatic
            },
        )
    }

    internal fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
        .split('&')
        .asSequence()
        .filter(String::isNotBlank)
        .map { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(key) to decode(value)
        }
        .filter { it.first.isNotBlank() }
        .associate { it }

    internal fun mergeProviderOrder(activeNames: List<String>, preferredOrder: List<String>): List<String> {
        val active = activeNames.filter(String::isNotBlank).distinct()
        val activeSet = active.toSet()
        return buildList {
            preferredOrder.filter { it in activeSet }.distinct().forEach(::add)
            active.filterNot { it in this }.forEach(::add)
        }
    }

    internal fun prioritizeProviderNames(
        activeNames: List<String>,
        preferredOrder: List<String>,
        lastProvider: String?,
    ): List<String> {
        val merged = mergeProviderOrder(activeNames, preferredOrder)
        val last = lastProvider?.takeIf { it in merged } ?: return merged
        return listOf(last) + merged.filterNot { it == last }
    }

    internal fun mediaIdentity(request: Request): String = when {
        request.tmdbEpisodeId != null -> "tmdbEpisode:${request.tmdbEpisodeId}"
        request.tmdbId != null -> buildString {
            append("tmdb:${request.kind}:${request.tmdbId}")
            request.season?.let { append(":s$it") }
            request.episode?.let { append(":e$it") }
        }
        request.imdbId != null -> buildString {
            append("imdb:${request.kind}:${request.imdbId}")
            request.season?.let { append(":s$it") }
            request.episode?.let { append(":e$it") }
        }
        else -> "title:${request.kind}:${normalizeTitle(request.title)}:${request.year ?: 0}:${request.season ?: 0}:${request.episode ?: 0}"
    }

    private suspend fun awaitProviders(activity: FragmentActivity): List<MainAPI> {
        repeat(48) {
            val activeNames = activity.getApiSettings()
            val current = apis.withLock {
                apis.filter { api -> activeNames.contains(api.name) }
            }
            if (current.isNotEmpty()) return current.distinctBy { it.name }
            delay(250)
        }
        return emptyList()
    }

    private fun orderProviders(
        activity: FragmentActivity,
        providers: List<MainAPI>,
        request: Request,
    ): List<MainAPI> {
        if (providers.isEmpty()) return emptyList()
        val byName = providers.associateBy { it.name }
        val names = prioritizeProviderNames(
            activeNames = providers.map { it.name },
            preferredOrder = readProviderOrder(activity),
            lastProvider = readLastProvider(activity, request),
        )
        return names.mapNotNull(byName::get)
    }

    private suspend fun resolveAndExecuteAutomatic(
        activity: FragmentActivity,
        providers: List<MainAPI>,
        request: Request,
    ): Boolean = coroutineScope {
        if (providers.isEmpty()) return@coroutineScope false
        val semaphore = Semaphore(4)
        val jobs = providers.map { api ->
            async { semaphore.withPermit { resolveProvider(api, request) } }
        }

        for ((index, job) in jobs.withIndex()) {
            val match = job.await() ?: continue

            if (request.kind == MediaKind.Series && (request.season == null || request.episode == null)) {
                jobs.drop(index + 1).forEach { it.cancel() }
                openResolvedDetails(activity, match.response)
                rememberLastProvider(activity, request, match.response.apiName)
                return@coroutineScope true
            }

            val prepared = preparePlayback(match.response, request) ?: continue
            if (hasPlayableLinks(prepared)) {
                jobs.drop(index + 1).forEach { it.cancel() }
                launchPreparedPlayback(activity, prepared)
                rememberLastProvider(activity, request, prepared.providerName)
                return@coroutineScope true
            }
        }
        false
    }

    private suspend fun resolveAll(providers: List<MainAPI>, request: Request): List<Match> = coroutineScope {
        if (providers.isEmpty()) return@coroutineScope emptyList()
        val semaphore = Semaphore(4)
        providers.map { api ->
            async { semaphore.withPermit { resolveProvider(api, request) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveProvider(api: MainAPI, request: Request): Match? {
        if (!supportsRequestedKind(api, request.kind)) return null
        val repo = APIRepository(api)

        request.imdbId?.let { imdbId ->
            if (SyncIdName.Imdb in api.supportedSyncNames) {
                val loaded = withTimeoutOrNull(7_000) {
                    val url = api.getLoadUrl(SyncIdName.Imdb, imdbId) ?: return@withTimeoutOrNull null
                    (repo.load(url) as? Resource.Success)?.value
                }
                if (loaded != null && loadedMatches(loaded, request, trustIdentity = true) && supportsExactRequest(loaded, request)) {
                    return Match(loaded)
                }
            }
        }

        val titles = listOfNotNull(request.title, request.originalTitle)
            .map(::normalizeWhitespace)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeTitle)

        for (query in titles) {
            val candidates = withTimeoutOrNull(8_000) {
                when (val search = repo.search(query, 1)) {
                    is Resource.Success -> search.value.items
                    else -> emptyList()
                }
            }.orEmpty()
                .filter { candidate -> searchCandidateMatches(candidate, request) }
                .distinctBy { it.url }

            for (candidate in candidates.take(3)) {
                val loaded = withTimeoutOrNull(8_000) {
                    when (val result = repo.load(candidate.url)) {
                        is Resource.Success -> result.value
                        else -> null
                    }
                } ?: continue
                if (loadedMatches(loaded, request, trustIdentity = false) && supportsExactRequest(loaded, request)) {
                    return Match(loaded)
                }
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

    private fun supportsExactRequest(response: LoadResponse, request: Request): Boolean = when {
        request.kind == MediaKind.Movie -> response is MovieLoadResponse && response.dataUrl.isNotBlank()
        request.kind == MediaKind.Episode && request.season != null && request.episode != null ->
            findEpisodeIndex(response, request.season, request.episode) >= 0
        else -> true
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

    private fun preparePlayback(response: LoadResponse, request: Request): PreparedPlayback? = when {
        request.kind == MediaKind.Movie || response.isMovie() -> prepareMovie(response)
        request.kind == MediaKind.Episode && request.season != null && request.episode != null ->
            prepareEpisode(response, request.season, request.episode)
        else -> null
    }

    private fun prepareMovie(response: LoadResponse): PreparedPlayback? {
        val movie = response as? MovieLoadResponse ?: return null
        if (movie.dataUrl.isBlank()) return null
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
        return PreparedPlayback(
            generator = RepoLinkGenerator(listOf(result), response),
            index = 0,
            syncData = HashMap(response.syncData),
            providerName = response.apiName,
        )
    }

    private fun prepareEpisode(
        response: LoadResponse,
        requestedSeason: Int,
        requestedEpisode: Int,
    ): PreparedPlayback? {
        val series = response as? TvSeriesLoadResponse ?: return null
        val selectedIndex = findEpisodeIndex(series, requestedSeason, requestedEpisode)
        if (selectedIndex < 0) return null
        val displaySeasons = series.seasonNames?.associate { it.season to it.displaySeason }.orEmpty()
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
        return PreparedPlayback(
            generator = RepoLinkGenerator(results, response),
            index = selectedIndex,
            syncData = HashMap(response.syncData),
            providerName = response.apiName,
        )
    }

    private fun findEpisodeIndex(response: LoadResponse, requestedSeason: Int, requestedEpisode: Int): Int {
        val series = response as? TvSeriesLoadResponse ?: return -1
        val displaySeasons = series.seasonNames?.associate { it.season to it.displaySeason }.orEmpty()
        return series.episodes.indexOfFirst { ep ->
            val displaySeason = ep.season?.let { displaySeasons[it] ?: it }
            displaySeason == requestedSeason && ep.episode == requestedEpisode
        }
    }

    private suspend fun hasPlayableLinks(prepared: PreparedPlayback): Boolean {
        var foundPlayableLink = false
        val completed = withTimeoutOrNull(20_000) {
            prepared.generator.generateLinks(
                clearCache = false,
                sourceTypes = LOADTYPE_INAPP,
                callback = { (link, uri) ->
                    if (link != null || uri != null) foundPlayableLink = true
                },
                subtitleCallback = {},
                offset = prepared.index,
                isCasting = false,
            )
        } ?: false
        return completed && foundPlayableLink
    }

    private fun launchPreparedPlayback(activity: FragmentActivity, prepared: PreparedPlayback) {
        main {
            activity.navigate(
                R.id.global_to_navigation_player,
                GeneratorPlayer.newInstance(prepared.generator, prepared.index, prepared.syncData),
            )
        }
    }

    private fun executeSelectedMatch(activity: FragmentActivity, request: Request, match: Match) {
        if (request.kind == MediaKind.Series && (request.season == null || request.episode == null)) {
            openResolvedDetails(activity, match.response)
            rememberLastProvider(activity, request, match.response.apiName)
            return
        }

        ioSafe {
            val prepared = preparePlayback(match.response, request)
            if (prepared != null && hasPlayableLinks(prepared)) {
                launchPreparedPlayback(activity, prepared)
                rememberLastProvider(activity, request, prepared.providerName)
            } else {
                main { showNoPlayableLinkDialog(activity, request, match.response.apiName) }
            }
        }
    }

    private fun episodeId(apiName: String, data: String, parentId: Int, index: Int): Int =
        "$apiName|$parentId|$index|$data".hashCode()

    private fun showProviderChooser(
        activity: FragmentActivity,
        request: Request,
        allProviders: List<MainAPI>,
        matches: List<Match>,
    ) {
        val labels = matches.map { it.response.apiName }.toTypedArray()
        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle("CloudStream-Anbieter")
            .setSingleChoiceItems(labels, -1) { openedDialog, which ->
                val match = matches.getOrNull(which) ?: return@setSingleChoiceItems
                openedDialog.dismiss()
                executeSelectedMatch(activity, request, match)
            }
            .setNeutralButton("Priorität") { _, _ ->
                showProviderPriorityDialog(activity, allProviders)
            }
            .setNegativeButton("Abbrechen", null)
            .create()
        dialog.setOnShowListener { dialog.listView?.requestFocus() }
        dialog.show()
    }

    private fun showNoDirectMatchDialog(
        activity: FragmentActivity,
        request: Request,
        allProviders: List<MainAPI>,
    ) {
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle("Kein direkter Treffer")
            .setMessage("Kein aktiver Provider liefert einen sicheren direkten Treffer. Du kannst die normale CloudStream-Suche öffnen oder die Provider-Priorität ändern.")
            .setPositiveButton("Suche öffnen") { _, _ -> fallbackToSearch(activity, request.title) }
            .setNeutralButton("Priorität") { _, _ -> showProviderPriorityDialog(activity, allProviders) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showNoPlayableLinkDialog(
        activity: FragmentActivity,
        request: Request,
        providerName: String,
    ) {
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle("Keine Wiedergabequelle")
            .setMessage("$providerName hat den Inhalt gefunden, aber aktuell keine abspielbare Quelle geliefert. Der Anbieter wird deshalb nicht als erfolgreich gespeichert.")
            .setPositiveButton("Suche öffnen") { _, _ -> fallbackToSearch(activity, request.title) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showProviderPriorityDialog(activity: FragmentActivity, providers: List<MainAPI>) {
        val activeNames = providers.map { it.name }.filter(String::isNotBlank).distinct()
        if (activeNames.isEmpty()) {
            AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                .setTitle("Provider-Priorität")
                .setMessage("Keine aktiven CloudStream-Provider gefunden.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val ordered = mergeProviderOrder(activeNames, readProviderOrder(activity)).toMutableList()
        var selected = 0
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_single_choice, ordered)
        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle("Provider-Priorität")
            .setMessage("Kurzes OK versucht zuerst den zuletzt wirklich abspielbaren Anbieter für diesen Inhalt, danach diese Reihenfolge. Ist ein Anbieter nicht verfügbar, wird automatisch der nächste versucht.")
            .setSingleChoiceItems(adapter, selected) { _, which -> selected = which }
            .setNeutralButton("Nach oben", null)
            .setNegativeButton("Nach unten", null)
            .setPositiveButton("Fertig") { _, _ -> saveProviderOrder(activity, ordered) }
            .create()

        dialog.setOnShowListener {
            fun refreshSelection() {
                adapter.notifyDataSetChanged()
                dialog.listView.setItemChecked(selected, true)
                dialog.listView.setSelection(selected)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (selected > 0) {
                    val item = ordered.removeAt(selected)
                    selected -= 1
                    ordered.add(selected, item)
                    refreshSelection()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (selected < ordered.lastIndex) {
                    val item = ordered.removeAt(selected)
                    selected += 1
                    ordered.add(selected, item)
                    refreshSelection()
                }
            }
            refreshSelection()
            dialog.listView.requestFocus()
        }
        dialog.show()
    }

    private fun openResolvedDetails(activity: FragmentActivity, response: LoadResponse) {
        main { activity.loadResult(response.url, response.apiName, response.name) }
    }

    private fun fallbackToSearch(activity: FragmentActivity, title: String) {
        main {
            MainActivity.handleAppIntentUrl(
                activity,
                "$SCHEME_SEARCH://${java.net.URLEncoder.encode(title, StandardCharsets.UTF_8.name()).replace("+", "%20")}",
                isWebview = false,
            )
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readProviderOrder(context: Context): List<String> =
        preferences(context).getString(PREF_PROVIDER_ORDER, null)
            ?.split(PROVIDER_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()

    private fun saveProviderOrder(context: Context, order: List<String>) {
        preferences(context).edit()
            .putString(PREF_PROVIDER_ORDER, order.distinct().joinToString(PROVIDER_SEPARATOR))
            .apply()
    }

    private fun readLastProvider(context: Context, request: Request): String? =
        preferences(context).getString(lastProviderPreferenceKey(request), null)

    private fun rememberLastProvider(context: Context, request: Request, providerName: String) {
        if (providerName.isBlank()) return
        preferences(context).edit()
            .putString(lastProviderPreferenceKey(request), providerName)
            .apply()
    }

    private fun lastProviderPreferenceKey(request: Request): String =
        "$PREF_LAST_PROVIDER_PREFIX${sha256(mediaIdentity(request)).take(24)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private const val SCHEME_SEARCH = "cloudstreamsearch"
    private const val PREFS_NAME = "i_launcher_direct_play"
    private const val PREF_PROVIDER_ORDER = "provider_order"
    private const val PREF_LAST_PROVIDER_PREFIX = "last_provider_"
    private const val PROVIDER_SEPARATOR = "\u001F"
}
