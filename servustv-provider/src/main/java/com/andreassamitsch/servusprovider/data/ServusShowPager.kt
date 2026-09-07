package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusApi
import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusCollectionRefDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Local-first in-app show pager.
 *
 * Opening a show performs one root-product request and one first-page request per advertised
 * collection so the API's editorial structure can be classified. Recommendation/playnet rails stop
 * there. Only CONTENT collections get cursors, and their already-fetched first page is reused. Product
 * details are hydrated only for the small visible batch and only when the collection card/cache does
 * not already contain enough metadata.
 */
class ServusShowPager(
    context: Context,
    private val api: ServusApi = ServusNetwork.api,
) {
    private val appContext = context.applicationContext
    private val sessionStore = ServusSessionStore(appContext, api)
    private val hubStore = ServusHubStore(appContext)
    private val detailSemaphore = Semaphore(DETAIL_PARALLELISM)
    private val collectionSemaphore = Semaphore(COLLECTION_DISCOVERY_PARALLELISM)
    private val states = mutableMapOf<String, PagingState>()

    data class PageResult(
        val show: ServusShow,
        val hasMore: Boolean,
    )

    suspend fun refresh(cachedShow: ServusShow): PageResult {
        val session = sessionStore.get()
        val detail = runCatching { api.product(session.countryCode, cachedShow.id) }.getOrNull()
        val title = detail?.title?.takeIf { !it.isNullOrBlank() } ?: cachedShow.title
        val resources = ServusCatalogPolicy.mergeMediaResources(
            fallback = emptyMap(),
            authoritative = detail?.mediaResources.orEmpty(),
        )
        val officialLogo = ServusCatalogPolicy.titleTreatment(cachedShow.id, resources)
        val baseShow = cachedShow.copy(
            title = title,
            description = detail?.longDescription?.takeIf { it.isNotBlank() }
                ?: detail?.shortDescription?.takeIf { it.isNotBlank() }
                ?: cachedShow.description,
            artworkUri = ServusCatalogPolicy.landscapeArtwork(cachedShow.id, resources)
                ?: cachedShow.artworkUri,
            squareArtworkUri = ServusCatalogPolicy.squareArtwork(cachedShow.id, resources)
                ?: cachedShow.squareArtworkUri,
            logoUri = ServusBranding.logoUriForShow(cachedShow.id, officialLogo ?: cachedShow.logoUri),
        )

        val previews = discoverCollections(
            market = session.countryCode,
            showId = cachedShow.id,
            showTitle = title,
            refs = detail?.collections.orEmpty(),
        )
        val collections = previews.map { preview ->
            ServusShowCollection(
                id = preview.id,
                title = preview.title,
                listType = preview.response.listType,
                type = preview.response.type,
                role = preview.role,
                contentShowId = preview.contentShowId,
                contentShowTitle = preview.contentShowTitle,
                episodes = cachedShow.collections
                    .firstOrNull { it.id == preview.id }
                    ?.episodes
                    .orEmpty(),
            )
        }
        val state = PagingState(
            market = session.countryCode,
            show = baseShow.copy(collections = collections),
            cursors = previews
                .filter { it.role == ServusCollectionRole.CONTENT }
                .map { preview ->
                    CollectionCursor(
                        id = preview.id,
                        fallbackLabel = preview.title,
                        label = preview.title,
                        contentShowId = preview.contentShowId,
                        contentShowTitle = preview.contentShowTitle,
                        nextOffset = ServusCatalogPolicy.nextOffset(preview.response.meta?.next),
                        pagesLoaded = 1,
                        exhausted = ServusCatalogPolicy.nextOffset(preview.response.meta?.next) == null,
                    )
                }
                .toMutableList(),
            sourceCards = mutableListOf(),
            hydratedIds = mutableSetOf(),
        )
        previews.filter { it.role == ServusCollectionRole.CONTENT }.forEach { preview ->
            addSourceCards(
                state,
                preview.response.cards.map { card -> preview.toSourcedCard(card, title) },
            )
        }
        states[cachedShow.id] = state
        persistShow(state.show)
        return loadNextInternal(state)
    }

    suspend fun loadNext(showId: String): PageResult? =
        states[showId]?.let { loadNextInternal(it) }

    private suspend fun discoverCollections(
        market: String,
        showId: String,
        showTitle: String,
        refs: List<ServusCollectionRefDto>,
    ): List<CollectionPreview> = coroutineScope {
        refs.asSequence()
            .filter { !it.id.isNullOrBlank() }
            .take(MAX_SHOW_COLLECTIONS)
            .map { ref ->
                async {
                    collectionSemaphore.withPermit {
                        val id = requireNotNull(ref.id)
                        val response = runCatching { api.collection(market, id, 0) }.getOrNull()
                            ?: return@withPermit null
                        val role = ServusCollectionPolicy.classify(response, showId, showTitle)
                        CollectionPreview(
                            id = id,
                            title = ServusCollectionPolicy.displayTitle(response, ref.label),
                            role = role,
                            contentShowId = ServusCollectionPolicy.contentShowId(showId, response),
                            contentShowTitle = ServusCollectionPolicy.contentShowTitle(showTitle, response),
                            response = response,
                        )
                    }
                }
            }
            .toList()
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun loadNextInternal(state: PagingState): PageResult {
        var eligible = eligibleCandidates(state)
        var guard = 0
        while (
            eligible.size < ServusShowPagingPolicy.PAGE_SIZE &&
            hasMoreSourcePages(state) &&
            guard < MAX_FETCH_ROUNDS_PER_PAGE
        ) {
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
        state.show = state.show.copy(
            episodes = mergedEpisodes,
            collections = state.show.collections.map { collection ->
                if (collection.role != ServusCollectionRole.CONTENT) return@map collection
                collection.copy(
                    episodes = mergedEpisodes.filter { it.sourceCollectionId == collection.id },
                )
            },
        )
        persistShow(state.show)

        return PageResult(
            show = state.show,
            hasMore = eligibleCandidates(state).isNotEmpty() || hasMoreSourcePages(state),
        )
    }

    private suspend fun fetchNextCollectionRound(state: PagingState): Int = coroutineScope {
        val active = state.cursors.filter { !it.exhausted }
        if (active.isEmpty()) return@coroutineScope 0

        val loaded = active.map { cursor ->
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
                            ownerShowId = cursor.contentShowId,
                            ownerShowTitle = cursor.contentShowTitle,
                            collectionLabel = label,
                        ) ?: ServusNewsPolicy.contentKind(card),
                        contentShowId = cursor.contentShowId,
                        contentShowTitle = cursor.contentShowTitle,
                    )
                }
            }
        }.awaitAll().flatten()

        addSourceCards(state, loaded)
    }

    private fun addSourceCards(state: PagingState, loaded: List<ServusSourcedCard>): Int {
        val known = state.sourceCards.mapNotNullTo(mutableSetOf()) { it.card.id }
        var added = 0
        loaded.forEach { candidate ->
            val id = candidate.card.id
            if (id == null || known.add(id)) {
                state.sourceCards += candidate
                added++
            }
        }
        return added
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
                    isVideoLike(card)
            }
            .distinctBy { it.card.id }
            .toList()

        val full = eligible.filter { isFullEpisode(it.card) }
        val unknown = eligible.filter { it.card.contentType.isNullOrBlank() }
        val clips = eligible.filterNot { isFullEpisode(it.card) || it.card.contentType.isNullOrBlank() }
        return (full + unknown + clips).distinctBy { it.card.id }
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
                    val cached = cachedById[id]
                    val detail = if (needsProductDetail(card, cached)) {
                        runCatching { api.product(state.market, id) }.getOrNull()
                    } else {
                        null
                    }
                    val merged = detail?.let { ServusCatalogPolicy.mergeEpisodeProduct(card, it) } ?: card
                    val resolvedCandidate = candidate.copy(
                        card = merged,
                        contentShowId = parentShowIdFromProduct(merged) ?: candidate.contentShowId,
                    )
                    val effectiveShowId = resolvedCandidate.contentShowId ?: state.show.id
                    val effectiveShowTitle = resolvedCandidate.contentShowTitle ?: state.show.title
                    val effectiveLogo = if (effectiveShowId == state.show.id) {
                        state.show.logoUri
                    } else {
                        ServusBranding.logoUriForShow(effectiveShowId, null)
                    }
                    val fresh = ServusCatalogPolicy.toShowEpisode(
                        candidate = resolvedCandidate,
                        showId = effectiveShowId,
                        showTitle = effectiveShowTitle,
                        categoryId = state.show.categoryId,
                        categoryTitle = state.show.categoryTitle,
                        showLogoUri = effectiveLogo,
                        nowMillis = System.currentTimeMillis(),
                    ) ?: return@withPermit cached

                    ServusBranding.canonicalizeEpisode(
                        fresh.copy(
                            description = fresh.description ?: cached?.description,
                            publishedAtMillis = fresh.publishedAtMillis ?: cached?.publishedAtMillis,
                            seasonNumber = fresh.seasonNumber ?: cached?.seasonNumber,
                            episodeNumber = fresh.episodeNumber ?: cached?.episodeNumber,
                            sourceCollectionId = fresh.sourceCollectionId ?: cached?.sourceCollectionId,
                            sourceCollectionTitle = fresh.sourceCollectionTitle ?: cached?.sourceCollectionTitle,
                        ),
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun needsProductDetail(card: ServusCardDto, cached: ServusNewsEpisode?): Boolean =
        card.duration?.let { it > 0L } != true ||
            card.longDescription.isNullOrBlank() && card.shortDescription.isNullOrBlank() ||
            card.sunriseTimestamp.isNullOrBlank() && cached?.publishedAtMillis == null ||
            card.seasonNumber == null && card.episodeNumber == null &&
                card.deeplinkPlaylist.isNullOrBlank() && card.nextPlaylist.isNullOrBlank()

    private fun parentShowIdFromProduct(card: ServusCardDto): String? = sequenceOf(
        card.deeplinkPlaylist,
        card.nextPlaylist,
    ).filterNotNull()
        .mapNotNull { playlist ->
            playlist.takeIf { it.endsWith(ALL_EPISODES_SUFFIX) }
                ?.removeSuffix(ALL_EPISODES_SUFFIX)
                ?.takeIf { it.isNotBlank() }
        }
        .firstOrNull()

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
        card.type.equals("video", ignoreCase = true) ||
            card.contentType.equals("episode", ignoreCase = true) ||
            card.contentType.equals("film", ignoreCase = true) ||
            card.contentType.equals("clip", ignoreCase = true)

    private fun isFullEpisode(card: ServusCardDto): Boolean =
        card.contentType.equals("episode", ignoreCase = true) ||
            card.contentType.equals("film", ignoreCase = true)

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
        var label: String?,
        val contentShowId: String,
        val contentShowTitle: String,
        var nextOffset: Int?,
        var pagesLoaded: Int,
        var exhausted: Boolean,
    )

    private data class CollectionPreview(
        val id: String,
        val title: String,
        val role: ServusCollectionRole,
        val contentShowId: String,
        val contentShowTitle: String,
        val response: SearchResponseDto,
    ) {
        fun toSourcedCard(card: ServusCardDto, ownerShowTitle: String): ServusSourcedCard =
            ServusSourcedCard(
                card = card,
                sourceCollectionId = id,
                sourceCollectionLabel = title,
                contentKindHint = ServusCatalogPolicy.contentKindForCollection(
                    ownerShowId = contentShowId,
                    ownerShowTitle = contentShowTitle.ifBlank { ownerShowTitle },
                    collectionLabel = title,
                ) ?: ServusNewsPolicy.contentKind(card),
                contentShowId = contentShowId,
                contentShowTitle = contentShowTitle,
            )
    }

    private companion object {
        const val DETAIL_PARALLELISM = 4
        const val COLLECTION_DISCOVERY_PARALLELISM = 4
        const val MAX_SHOW_COLLECTIONS = 8
        const val MAX_PAGES_PER_COLLECTION = 12
        const val MAX_FETCH_ROUNDS_PER_PAGE = 4
        const val ALL_EPISODES_SUFFIX = ":all_episodes"
    }
}
