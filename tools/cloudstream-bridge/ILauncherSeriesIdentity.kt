package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * Only consolidate TV-series resume rows when their loaded metadata shares an external ID.
 * Keep all provider-local playback history; never guess identity from titles.
 */
internal object ILauncherSeriesIdentity {
    private const val PREFS = "ilauncher_series_identity_v1"

    internal data class Identity(val imdb: String?, val tmdb: String?) {
        fun conflicts(other: Identity): Boolean =
            (imdb != null && other.imdb != null && imdb != other.imdb) ||
            (tmdb != null && other.tmdb != null && tmdb != other.tmdb)

        fun sharesId(other: Identity): Boolean =
            (imdb != null && imdb == other.imdb) ||
            (tmdb != null && tmdb == other.tmdb)

        fun matches(other: Identity): Boolean = sharesId(other) && !conflicts(other)
    }

    internal data class Candidate(
        val index: Int, val provider: String, val identity: Identity,
        val season: Int, val episode: Int,
    )

    internal fun winners(items: List<Candidate>): Set<Int> {
        // A conflicting known ID makes the shared ID ambiguous. Keep such rows separate.
        val ambiguous = items.filter { a ->
            items.any { b -> a.identity.sharesId(b.identity) && a.identity.conflicts(b.identity) }
        }.map { it.index }.toSet()
        return items.filter { a ->
            a.index in ambiguous || items.none { b ->
                b.index !in ambiguous && b.provider != a.provider &&
                    a.identity.matches(b.identity) &&
                    (b.season > a.season ||
                        b.season == a.season && b.episode > a.episode ||
                        b.season == a.season && b.episode == a.episode && b.index < a.index)
            }
        }.map { it.index }.toSet()
    }

    private fun prefs() = CloudStreamApp.context?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun key(parent: Int, provider: String): String =
        "${DataStoreHelper.currentAccount}:$parent:$provider"

    private fun normalize(imdb: String?, tmdb: String?): Identity? {
        val i = imdb?.trim()?.lowercase()?.takeIf { it.matches(Regex("tt[0-9]+")) }
        val t = tmdb?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.toString()
        return if (i == null && t == null) null else Identity(i, t)
    }

    fun remember(parent: Int, response: LoadResponse) {
        if (response !is TvSeriesLoadResponse || response.type != TvType.TvSeries) return
        val identity = normalize(response.getImdbId(), response.getTMDbId()) ?: return
        prefs()?.edit()?.putString(key(parent, response.apiName),
            "${identity.imdb.orEmpty()}|${identity.tmdb.orEmpty()}")?.apply()
    }

    private const val RESOLVE_RETRY_MS = 6L * 60 * 60 * 1000

    internal fun normalizedTitle(value: String?): String =
        value.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    internal fun sameTitle(title: String, name: String?, original: String?): Boolean {
        val expected = normalizedTitle(title)
        return expected.isNotBlank() && (expected == normalizedTitle(name) || expected == normalizedTitle(original))
    }

    internal fun yearMatch(source: Int?, result: Int?): Boolean =
        source != null && result != null && source == result

    internal fun meaningfulEpisodeName(value: String?): String? =
        normalizedTitle(value).takeIf {
            it.length >= 4 && !it.matches(Regex("(episode|folge|episod|part|teil) ?[0-9]+")) &&
                !it.matches(Regex("[0-9]+"))
        }

    /** Only metadata requests, never playback or provider URLs. Keep existing verified IDs. */
    suspend fun resolveMissing(parent: Int, series: TvSeriesLoadResponse): Boolean {
        val account = DataStoreHelper.currentAccount
        if (series.type != TvType.TvSeries) return false
        remember(parent, series)
        if (cachedIdentity(parent, series.apiName) != null) return true
        val storage = prefs() ?: return false
        val cacheKey = key(parent, series.apiName)
        val retryKey = "resolve_attempt_v2:$cacheKey"
        val now = System.currentTimeMillis()
        if (storage.getLong(retryKey, 0L) + RESOLVE_RETRY_MS > now) return false
        storage.edit().putLong(retryKey, now).apply()
        val match = try {
            kotlinx.coroutines.withTimeoutOrNull(8000L) {
                val provider = com.lagradost.cloudstream3.metaproviders.TmdbProvider()
                val exact = provider.bridgeSearchTvIdentity(series.name).filter {
                    it.id != null && sameTitle(series.name, it.name, it.originalName)
                }.distinctBy { it.id }
                val byYear = exact.filter { yearMatch(series.year, it.year) }
                if (byYear.size == 1) byYear.single()
                else if (byYear.size > 1) null
                else {
                    // Missing year: require two distinct, matching named episodes at the same
                    // coordinates, in either German or English. Never merge solely by title.
                    val displaySeasons = series.seasonNames?.associate { it.season to it.displaySeason }.orEmpty()
                    val named = series.episodes.mapNotNull { ep ->
                        val season = ep.season?.let { displaySeasons[it] ?: it } ?: return@mapNotNull null
                        val number = ep.episode ?: return@mapNotNull null
                        val name = meaningfulEpisodeName(ep.name) ?: return@mapNotNull null
                        Triple(season, number, name)
                    }.distinctBy { it.first to it.second }.groupBy { it.first }
                    val sample = named.entries.filter { it.key > 0 && it.value.size >= 2 }
                        .minByOrNull { it.key }
                    if (sample == null || exact.size > 3) null else {
                        exact.filter { candidate ->
                            if (series.year != null && candidate.year != null && series.year != candidate.year) return@filter false
                            val id = candidate.id ?: return@filter false
                            val german = provider.bridgeTvIdentitySeason(id, sample.key, "de-DE")
                            val english = provider.bridgeTvIdentitySeason(id, sample.key, "en-US")
                            sample.value.filter { (_, number, name) ->
                                (german + english).any { it.episodeNumber == number && meaningfulEpisodeName(it.name) == name }
                            }.map { it.third }.distinct().size >= 2
                        }.singleOrNull()
                    }
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { null }
        if (DataStoreHelper.currentAccount != account) return false
        val tmdb = match?.id?.takeIf { it > 0 }?.toString()
        if (tmdb == null) {
            android.util.Log.i("ILauncherSeriesIdentity", "provider=${series.apiName} parent=$parent outcome=unresolved")
            return false
        }
        if (cachedIdentity(parent, series.apiName) == null) {
            storage.edit().putString(cacheKey, "|$tmdb").apply()
        }
        android.util.Log.i("ILauncherSeriesIdentity", "provider=${series.apiName} parent=$parent outcome=resolved tmdb=$tmdb")
        return true
    }

    fun cachedIdentity(parent: Int, provider: String): Identity? {
        val saved = prefs()?.getString(key(parent, provider), null)?.split('|', limit = 2) ?: return null
        return normalize(saved.getOrNull(0), saved.getOrNull(1))
    }

    internal data class Publication(
        val provider: String, val parent: Int, val identity: Identity?,
        val season: Int, val episode: Int, val engagement: Long = 0L,
    )

    /**
     * Resume rows already passed the regular resume selection. An announcement must not compete
     * with a resume for the same verified series. If no resume exists, keep the furthest verified
     * announced episode, then the most recent completion. Never merge conflicting external IDs.
     */
    internal fun announcementWinners(resume: List<Publication>, announcements: List<Publication>): Set<Int> {
        val all = resume + announcements
        fun ambiguous(a: Publication): Boolean = a.identity?.let { id ->
            all.any { b -> b.identity?.let { id.sharesId(it) && id.conflicts(it) } == true }
        } ?: false
        fun same(a: Publication, b: Publication): Boolean {
            if (a.provider == b.provider && a.parent == b.parent) return true
            if (ambiguous(a) || ambiguous(b)) return false
            return a.identity?.let { id -> b.identity?.let(id::matches) } == true
        }
        val ranking = compareBy<Publication> { it.season }.thenBy { it.episode }.thenBy { it.engagement }
        return announcements.indices.filter { index ->
            val candidate = announcements[index]
            resume.none { same(candidate, it) } && announcements.indices.none { other ->
                other != index && same(candidate, announcements[other]) &&
                    (ranking.compare(announcements[other], candidate) > 0 ||
                        ranking.compare(announcements[other], candidate) == 0 && other < index)
            }
        }.toSet()
    }

    fun resumePublication(card: DataStoreHelper.ResumeWatchingResult): Publication? {
        if (card.type != TvType.TvSeries || card.isFromDownload) return null
        val parent = card.parentId ?: return null
        return Publication(card.apiName, parent, cachedIdentity(parent, card.apiName),
            card.season ?: 0, card.episode ?: 0)
    }

    private fun candidate(index: Int, card: DataStoreHelper.ResumeWatchingResult): Candidate? {
        if (card.type != TvType.TvSeries || card.isFromDownload) return null
        val parent = card.parentId ?: return null
        val season = card.season?.takeIf { it > 0 } ?: return null
        val episode = card.episode?.takeIf { it > 0 } ?: return null
        val identity = cachedIdentity(parent, card.apiName) ?: return null
        return Candidate(index, card.apiName, identity, season, episode)
    }

    fun forWatchNext(cards: List<DataStoreHelper.ResumeWatchingResult>): List<DataStoreHelper.ResumeWatchingResult> {
        val known = cards.mapIndexedNotNull(::candidate)
        val indices = winners(known)
        val knownIndices = known.map { it.index }.toSet()
        return cards.filterIndexed { index, _ -> index !in knownIndices || index in indices }
    }

    fun preferredResume(
        base: DataStoreHelper.ResumeWatchingResult,
        cards: List<DataStoreHelper.ResumeWatchingResult>,
    ): DataStoreHelper.ResumeWatchingResult {
        val index = cards.indexOf(base)
        if (index < 0) return base
        val known = cards.mapIndexedNotNull(::candidate)
        val source = known.firstOrNull { it.index == index } ?: return base
        val indices = winners(known)
        if (index in indices) return base
        val winner = known.filter { it.index in indices && it.provider != source.provider &&
            it.identity.matches(source.identity) }
            .maxWithOrNull(compareBy<Candidate> { it.season }.thenBy { it.episode }.thenBy { -it.index })
            ?: return base
        return cards[winner.index]
    }
}
