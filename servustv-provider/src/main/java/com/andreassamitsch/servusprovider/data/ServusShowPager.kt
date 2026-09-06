package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusApi
import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

/**
 * In-app show pager.
 *
 * The global refresh intentionally does not walk every episode collection. When a show is opened,
 * this pager follows only the collection pages advertised by ServusTV and hydrates product details
 * in small batches. That keeps descriptions/season metadata accurate without front-loading dozens
 * of product requests or imposing the Android-TV-channel episode limit on the standalone UI.
 */
class ServusShowPager(
    context: Context,
    private val api: ServusApi = ServusNetwork.api,
) {
    private val appContext = context.applicationContext
    private val sessionStore = ServusSessionStore(appContext, api)
    private val hubStore = ServusHubStore(appContext)
    private val detailSemaphore = Semaphore(DETAIL_PARALLELISM)
    private val states = mutableMapOf<String, PagingState>()

    data class PageResult(
        val show: ServusShow,
        val hasMore: Boolean,
    )

    suspend fun refresh(cachedShow: ServusShow): PageResult {
        val session = sessionStore.get()
        val detail = runCatching { api.product(session.countryCode, cachedShow.id) }.getOrNull()
        val title = detail?.title?.takeIf { !it.isNullOrBlank() } ?: cachedShow.title
        val resources = detail?.mediaResources.orEmpty()
        val logo = ServusBranding.logoUriForShow(
            cachedShow.id,
            ServusCatalogPolicy.titleTreatment(cachedShow.id, resources) ?: cachedShow.logoUri,
        )
        val baseShow = cachedShow.copy(
            title = title,
            description = detail?.longDescription?.takeIf { it.isNotBlank() }
                ?: detail?.shortDescription?.takeIf { it.isNotBlank() }
                ?: cachedShow.description,
            artworkUri = ServusCatalogPolicy.landscapeArtwork(cachedShow.id, resources)
                ?: cachedShow.artworkUri,
            squareArtworkUri = ServusCatalogPolicy.squareArtwork(cachedShow.id, resources)
                ?: cachedShow.squareArtworkUri,
            logoUri = logo,
        )
        val cursors = detail?.collections.orEmpty()
            .filter { it.listType != "reference" && !it.id.isNullOrBlank() }
            .take(MAX_SHOW_COLLECTIONS)
            .map { ref ->
                CollectionCursor(
                    id = requireNotNull(ref.id),
                    fallbackLabel = ref.label,
                )
            }
            .toMutableList()

        val state = PagingState(
            market = session.countryCode,
            show = baseShow,
            cursors = cursors,
            sourceCards = mutableListOf(),
            hydratedIds = mutableSetOf(),
        )
        states[cachedShow.id] = state
        return loadNextInternal(state)
    }

    suspend fun loadNext(showId: String): PageResult? {
        val state = states[showId] ?: return null
        return loadNextInternal(state)
    }

    private suspend fun loadNextInternal(state: PagingState): PageResult {
        var eligible = eligibleCandidates(state)
        var guard = 0
        while (eligible.size < ServusShowPagingPolicy.PAGE_SIZE && hasMoreSourcePages(state) && guard < MAX_FETCH_ROUNDS_PER_PAGE) {
            val added = fetchNextCollectionRound(state)
            if (added == 0) break
            eligible = eligibleCandidates(state)
            guard++
        }

        val batch = eligible.take(ServusShowPagingPolicy.PAGE_SIZE)
        val hydrated = hydrateBatch(state, batch)
        state.hydratedIds += batch.mapNotNull { it.card.id }
        val mergedEpisodes = ServusShowPagingPolicy.mergeEpisodes(
            cached = state.show.episodes,
            fresh = hydrated,
        )
        state.show = state.show.copy(episodes = mergedEpisodes)
        persistShow(state.show)

        return PageResult(
            show = state.show,
            hasMore = eligibleCandidates(state).isNotEmpty() || hasMoreSourcePages(state),
        )
    }

    private suspend fun fetchNextCollectionRound(state: PagingState): Int = coroutineScope {
        val active = state.cursors.filter { !it.exhausted }
        if (active.isEmpty()) return@coroutineScope 0

        active.map { cursor ->
            async {
                val offset = cursor.nextOffset ?: return@async emptyList<ServusSourcedCard>()
                val response = runCatching { api.collection(state.market, cursor.id, offset) }.getOrNull()
                if (response == null) {
                    cursor.exhausted = true
                    return@async emptyList()
                }
                cursor.pagesLoaded++
                val label = response.label?.takeIf { it.isNotBlank() } ?: cursor.fallbackLabel
                cursor.label = label
                val next = ServusCatalogPolicy.nextOffset(response.meta?.next)
                cursor.nextOffset = next
                cursor.exhausted = next == null || cursor.pagesLoaded >= MAX_PAGES_PER_COLLECTION
                response.cards.map { card ->
                    ServusSourcedCard(
                        card = card,
                        sourceCollectionId = cursor.id,
                        sourceCollectionLabel = label,
                        contentKindHint = ServusCatalogPolicy.contentKindForCollection(
                            ownerShowId = state.show.id,
                            ownerShowTitle = state.show.title,
                            collectionLabel = label,
                        ) ?: ServusNewsPolicy.contentKind(card),
                    )
                }
            }
        }.awaitAll().flatten().also { loaded ->
            val known = state.sourceCards.mapNotNullTo(mutableSetOf()) { it.card.id }
            loaded.forEach { candidate ->
                val id = candidate.card.id
                if (id == null || known.add(id)) state.sourceCards += candidate
            }
        }.size
    }

    private fun eligibleCandidates(state: PagingState): List<ServusSourcedCard> {
        val eligible = state.sourceCards.asSequence()
            .filter { candidate ->
                val card = candidate.card
                val id = card.id
                !id.isNullOrBlank() &&
                    id !in state.hydratedIds &&
                    card.title?.isNotBlank() == true &&
                    card.playable != false &&
                    isVideoLike(card) &&
                    belongsToOpenedShow(candidate, state.show)
            }
            .distinctBy { it.card.id }
            .toList()

        val full = eligible.filter { isFullEpisode(it.card) }
        val unknown = eligible.filter { it.card.contentType.isNullOrBlank() }
        val clips = eligible.filterNot { isFullEpisode(it.card) || it.card.contentType.isNullOrBlank() }
        return (full + unknown + clips).distinctBy { it.card.id }
    }

    private fun belongsToOpenedShow(candidate: ServusSourcedCard, show: ServusShow): Boolean {
        if (ServusCatalogPolicy.belongsToShow(candidate, show.id, show.title)) return true
        if (show.id != ServusBranding.WEATHER_90_SECONDS_SHOW_ID) return false

        // ServusTV's dedicated weather-90 page exposes an editorial "Aktuelle Sendungen" rail.
        // That collection is authoritative even when individual cards still use "Servus Wetter"
        // as show_name, which would otherwise make the generic exact-name membership check reject it.
        val source = normalizeWords(candidate.sourceCollectionLabel.orEmpty())
        return source == "aktuelle sendungen"
    }

    private suspend fun hydrateBatch(
        state: PagingState,
        candidates: List<ServusSourcedCard>,
    ): List<ServusNewsEpisode> = coroutineScope {
        val cachedById = state.show.episodes.associateBy { it.id }
        candidates.map { candidate ->
            async {
                detailSemaphore.withPermit {
                    val card = candidate.card
                    val id = card.id ?: return@withPermit null
                    val detail = runCatching { api.product(state.market, id) }.getOrNull()
                    val mergedCard = detail?.let { ServusCatalogPolicy.mergeEpisodeProduct(card, it) } ?: card
                    val mergedCandidate = candidate.copy(card = mergedCard)
                    val fresh = ServusCatalogPolicy.toShowEpisode(
                        candidate = mergedCandidate,
                        showId = state.show.id,
                        showTitle = state.show.title,
                        categoryId = state.show.categoryId,
                        categoryTitle = state.show.categoryTitle,
                        showLogoUri = state.show.logoUri,
                        nowMillis = System.currentTimeMillis(),
                    ) ?: return@withPermit cachedById[id]

                    val cached = cachedById[id]
                    ServusBranding.canonicalizeEpisode(
                        fresh.copy(
                            description = fresh.description ?: cached?.description,
                            publishedAtMillis = fresh.publishedAtMillis ?: cached?.publishedAtMillis,
                            seasonNumber = mergedCard.seasonNumber ?: cached?.seasonNumber,
                            episodeNumber = mergedCard.episodeNumber ?: cached?.episodeNumber,
                        ),
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun persistShow(show: ServusShow) {
        val categories = hubStore.loadCategories()
        var changed = false
        val updated = categories.map { category ->
            category.copy(
                shows = category.shows.map { existing ->
                    if (existing.id != show.id) existing else {
                        changed = true
                        show.copy(categoryId = category.id, categoryTitle = category.title)
                    }
                },
            )
        }
        if (changed) hubStore.saveCatalogContent(updated)
    }

    private fun hasMoreSourcePages(state: PagingState): Boolean = state.cursors.any { !it.exhausted }

    private fun isVideoLike(card: ServusCardDto): Boolean =
        card.type == "video" ||
            card.contentType.equals("episode", ignoreCase = true) ||
            card.contentType.equals("film", ignoreCase = true) ||
            card.contentType.equals("clip", ignoreCase = true)

    private fun isFullEpisode(card: ServusCardDto): Boolean =
        card.contentType.equals("episode", ignoreCase = true) ||
            card.contentType.equals("film", ignoreCase = true)

    private fun normalizeWords(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace('–', '-')
        .replace(Regex("""[^a-z0-9äöüß]+"""), " ")
        .trim()

    private data class PagingState(
        val market: String,
        var show: ServusShow,
        val cursors: MutableList<CollectionCursor>,
        val sourceCards: MutableList<ServusSourcedCard>,
        val hydratedIds: MutableSet<String>,
    )

    private data class CollectionCursor(
        val id: String,
        val fallbackLabel: String?,
        var label: String? = fallbackLabel,
        var nextOffset: Int? = 0,
        var pagesLoaded: Int = 0,
        var exhausted: Boolean = false,
    )

    private companion object {
        const val DETAIL_PARALLELISM = 4
        const val MAX_SHOW_COLLECTIONS = 5
        const val MAX_PAGES_PER_COLLECTION = 12
        const val MAX_FETCH_ROUNDS_PER_PAGE = 4
    }
}
