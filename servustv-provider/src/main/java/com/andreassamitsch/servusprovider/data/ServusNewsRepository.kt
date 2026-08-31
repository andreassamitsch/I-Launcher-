package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusApi
import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.andreassamitsch.servusprovider.tv.ServusChannelPublisher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ServusNewsRepository(
    context: Context,
    private val api: ServusApi = ServusNetwork.api,
) {
    private val appContext = context.applicationContext
    private val sessionStore = ServusSessionStore(appContext, api)
    private val newsStore = ServusNewsStore(appContext)
    private val channelPublisher = ServusChannelPublisher(appContext)

    fun cachedEpisodes(): List<ServusNewsEpisode> = newsStore.loadEpisodes()
    fun lastSuccessMillis(): Long = newsStore.lastSuccessMillis()
    fun lastError(): String? = newsStore.lastError()

    suspend fun refresh(): ServusRefreshResult {
        return try {
            val previousEpisodes = newsStore.loadEpisodes()
            val session = sessionStore.get()
            val market = session.countryCode
            val candidates = discoverCandidates(market)
            val details = fetchDetails(market, candidates)
            val episodes = ServusNewsPolicy.deduplicateEditions(
                details
                    .mapNotNull(ServusNewsPolicy::toFullNewsEpisode)
                    .distinctBy { it.id },
            ).take(MAX_EPISODES)

            check(episodes.isNotEmpty()) {
                "Keine vollständige Servus-Nachrichten-19:20-Sendung in den API-Ergebnissen gefunden"
            }

            val result = ServusRefreshResult(
                episodes = episodes,
                refreshedAtMillis = System.currentTimeMillis(),
            )
            newsStore.save(result)
            val contentChanged = previousEpisodes.map { ServusNewsPolicy.editionKey(it) to it.id } !=
                episodes.map { ServusNewsPolicy.editionKey(it) to it.id }
            if (contentChanged || !channelPublisher.isPublished()) {
                channelPublisher.publish(episodes)
            }
            result
        } catch (throwable: Throwable) {
            newsStore.saveError(throwable.message ?: throwable.javaClass.simpleName)
            throw throwable
        }
    }

    private suspend fun discoverCandidates(market: String): List<String> = coroutineScope {
        val searches = buildList {
            SEARCH_QUERIES.forEach { query ->
                SEARCH_OFFSETS.forEach { offset ->
                    add(async { api.search(market, query, offset) })
                }
            }
        }.awaitAll()

        val directCards = searches.flatMap { it.cards }
        val directIds = directCards
            .filter(ServusNewsPolicy::couldBelongToNews)
            .mapNotNull { it.id }
            .toMutableList()

        val newsPages = directCards.filter { card ->
            card.type == "page" && ServusNewsPolicy.couldBelongToNews(card)
        }
        val collectionIds = newsPages.mapNotNull { it.id }.distinct().take(MAX_NEWS_PAGES).map { pageId ->
            async {
                runCatching { api.product(market, pageId) }
                    .getOrNull()
                    ?.collections
                    .orEmpty()
                    .filterNot { it.listType == "reference" }
                    .mapNotNull { it.id }
            }
        }.awaitAll().flatten().distinct().take(MAX_COLLECTIONS)

        val collectionCards = collectionIds.map { collectionId ->
            async {
                runCatching { api.collection(market, collectionId, 0) }
                    .getOrNull()
                    ?.cards
                    .orEmpty()
            }
        }.awaitAll().flatten()

        directIds += collectionCards
            .filter(ServusNewsPolicy::couldBelongToNews)
            .mapNotNull { it.id }

        directIds.distinct().take(MAX_DETAIL_CANDIDATES)
    }

    private suspend fun fetchDetails(market: String, ids: List<String>): List<ServusCardDto> = coroutineScope {
        val semaphore = Semaphore(DETAIL_PARALLELISM)
        ids.map { id ->
            async {
                semaphore.withPermit {
                    runCatching { api.product(market, id) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    private companion object {
        val SEARCH_QUERIES = listOf("Servus Nachrichten", "Nachrichten 19:20")
        val SEARCH_OFFSETS = listOf(0, 15, 30)
        const val DETAIL_PARALLELISM = 6
        const val MAX_NEWS_PAGES = 4
        const val MAX_COLLECTIONS = 8
        const val MAX_DETAIL_CANDIDATES = 50
        const val MAX_EPISODES = 12
    }
}
