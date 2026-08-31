package com.andreassamitsch.servusprovider.data

import android.content.Context
import android.util.Log
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
        .sortedByDescending { it.publishedAtMillis }

    fun lastSuccessMillis(): Long = newsStore.lastSuccessMillis()
    fun lastError(): String? = newsStore.lastError()
    fun tvChannelSupported(): Boolean = channelPublisher.isSupported()

    suspend fun refresh(): ServusRefreshResult {
        return try {
            val previousEpisodes = newsStore.loadEpisodes()
            val session = sessionStore.get()
            val market = session.countryCode
            val candidates = discoverCandidates(market)
            val details = fetchDetails(market, candidates)
            val refreshNow = System.currentTimeMillis()
            val episodes = ServusNewsPolicy.deduplicateEpisodes(
                details.mapNotNull { card ->
                    ServusNewsPolicy.toSupportedEpisode(card, refreshNow)
                },
            ).take(MAX_EPISODES)

            check(episodes.isNotEmpty()) {
                "Keine unterstützte ServusTV-Sendung in den API-Ergebnissen gefunden"
            }

            val result = ServusRefreshResult(
                episodes = episodes,
                refreshedAtMillis = refreshNow,
            )
            // Standalone data is the primary result. TV-channel publication must never make a
            // successful phone/tablet refresh fail when there is no TvProvider on the device.
            newsStore.save(result)

            if (channelPublisher.isSupported()) {
                val contentChanged = previousEpisodes.map { ServusNewsPolicy.contentKey(it) to it.id } !=
                    episodes.map { ServusNewsPolicy.contentKey(it) to it.id }
                runCatching {
                    if (contentChanged || !channelPublisher.isPublished()) {
                        channelPublisher.publish(episodes)
                    }
                }.onFailure { throwable ->
                    Log.w(TAG, "TvProvider sync skipped after refresh: ${throwable.javaClass.simpleName}")
                }
            }
            result
        } catch (throwable: Throwable) {
            newsStore.saveError(throwable.message ?: throwable.javaClass.simpleName)
            throw throwable
        }
    }

    private suspend fun discoverCandidates(market: String): List<String> = coroutineScope {
        val responseGroups = SEARCH_QUERIES.map { query ->
            async {
                SEARCH_OFFSETS.map { offset ->
                    async { api.search(market, query, offset) }
                }.awaitAll()
            }
        }.awaitAll()

        val directCards = responseGroups.flatten().flatMap { it.cards }
        val directIds = responseGroups.flatMap { responses ->
            responses
                .flatMap { it.cards }
                .filter(ServusNewsPolicy::couldBelongToSupportedContent)
                .mapNotNull { it.id }
                .distinct()
                .take(MAX_DIRECT_IDS_PER_QUERY)
        }.toMutableList()

        // Page results are valuable because their collections often contain the newest episodes
        // even when generic search ranking is not strictly chronological.
        val contentPages = directCards.filter { card ->
            card.type == "page" && ServusNewsPolicy.couldBelongToSupportedContent(card)
        }
        val collectionIds = contentPages
            .mapNotNull { it.id }
            .distinct()
            .take(MAX_CONTENT_PAGES)
            .map { pageId ->
                async {
                    runCatching { api.product(market, pageId) }
                        .getOrNull()
                        ?.collections
                        .orEmpty()
                        .filterNot { it.listType == "reference" }
                        .mapNotNull { it.id }
                }
            }
            .awaitAll()
            .flatten()
            .distinct()
            .take(MAX_COLLECTIONS)

        val collectionCards = collectionIds.map { collectionId ->
            async {
                runCatching { api.collection(market, collectionId, 0) }
                    .getOrNull()
                    ?.cards
                    .orEmpty()
            }
        }.awaitAll().flatten()

        directIds += collectionCards
            .filter(ServusNewsPolicy::couldBelongToSupportedContent)
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
        const val TAG = "ServusRepository"
        val SEARCH_QUERIES = listOf(
            "Servus Nachrichten",
            "Nachrichten 19:20",
            "Servus Nachrichten in 90 Sekunden",
            "Der Wegscheider",
        )
        val SEARCH_OFFSETS = listOf(0, 15, 30)
        const val MAX_DIRECT_IDS_PER_QUERY = 16
        const val DETAIL_PARALLELISM = 6
        const val MAX_CONTENT_PAGES = 8
        const val MAX_COLLECTIONS = 12
        const val MAX_DETAIL_CANDIDATES = 72
        const val MAX_EPISODES = 40
    }
}
