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

    private fun candidate(index: Int, card: DataStoreHelper.ResumeWatchingResult): Candidate? {
        if (card.type != TvType.TvSeries || card.isFromDownload) return null
        val parent = card.parentId ?: return null
        val season = card.season?.takeIf { it > 0 } ?: return null
        val episode = card.episode?.takeIf { it > 0 } ?: return null
        val saved = prefs()?.getString(key(parent, card.apiName), null)?.split('|', limit = 2) ?: return null
        val identity = normalize(saved.getOrNull(0), saved.getOrNull(1)) ?: return null
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
