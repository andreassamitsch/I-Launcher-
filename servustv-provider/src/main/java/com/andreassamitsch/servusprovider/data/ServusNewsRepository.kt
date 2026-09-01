package com.andreassamitsch.servusprovider.data

import android.content.Context
import android.util.Log
import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusApi
import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.andreassamitsch.servusprovider.tv.ServusChannelPublisher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException

class ServusNewsRepository(
    context: Context,
    private val api: ServusApi = ServusNetwork.api,
) {
    private val appContext = context.applicationContext
    private val sessionStore = ServusSessionStore(appContext, api)
    private val newsStore = ServusNewsStore(appContext)
    private val hubStore = ServusHubStore(appContext)
    private val currentSelectionStore = ServusCurrentChannelSelectionStore(appContext)
    private val observedAvailabilityStore = ServusObservedAvailabilityStore(appContext)
    private val channelPublisher = ServusChannelPublisher(appContext)

    fun cachedEpisodes(): List<ServusNewsEpisode> = newsStore.loadEpisodes()
        .sortedWith(
            compareByDescending<ServusNewsEpisode> { ServusNewsPolicy.recencyMillis(it) ?: Long.MIN_VALUE },
        )

    fun cachedCategories(): List<ServusCategory> = hubStore.loadCategories().sortedBy { it.order }

    fun cachedLiveChannels(): List<ServusLiveChannel> = hubStore.loadLiveChannels()

    fun cachedShow(showId: String): ServusShow? = hubStore.findShow(showId)

    fun setCurrentShowSelected(showId: String, selected: Boolean) {
        val categories = hubStore.loadCategories()
        if (selected) {
            categories.asSequence()
                .flatMap { it.shows.asSequence() }
                .firstOrNull { it.id == showId }
                ?.let { show -> observedAvailabilityStore.baseline(show.episodes) }
        }
        currentSelectionStore.setSelected(showId, selected, categories)
    }

    fun lastSuccessMillis(): Long = newsStore.lastSuccessMillis()
    fun catalogLastSuccessMillis(): Long = hubStore.catalogLastSuccessMillis()
    fun liveLastSuccessMillis(): Long = hubStore.liveLastSuccessMillis()
    fun catalogDiagnostic(): String? = hubStore.catalogDiagnostic()
    fun lastError(): String? = newsStore.lastError()
    fun tvChannelSupported(): Boolean = channelPublisher.isSupported()

    /**
     * Fast data (Aktuelles + live guide) is refreshed on every run. The complete show catalogue is
     * deliberately slower: initial/manual refresh or every six hours. When the user explicitly
     * configures additional shows for Aktuelles, only those non-legacy shows get a lightweight
     * first-page refresh on the normal 15-minute worker cadence so newly available episodes do not
     * wait up to six hours.
     */
    suspend fun refresh(forceCatalog: Boolean = false): ServusRefreshResult {
        return try {
            val previousEpisodes = newsStore.loadEpisodes()
            val session = sessionStore.get()
            val market = session.countryCode
            val refreshNow = System.currentTimeMillis()
            val detectNewAvailability = observedAvailabilityStore.isInitialized()

            val candidates = discoverCurrentCandidates(market)
            val details = fetchDetails(market, candidates)
            val mappedEpisodes = ServusNewsPolicy.deduplicateEpisodes(
                details.mapNotNull { card ->
                    ServusNewsPolicy.toSupportedEpisode(card, refreshNow)
                },
            ).take(MAX_CURRENT_EPISODES)
            val episodes = observedAvailabilityStore.annotateNewlyObserved(
                episodes = mappedEpisodes,
                observedAtMillis = refreshNow,
                detectNewItems = detectNewAvailability,
            )

            check(episodes.isNotEmpty()) {
                "Keine unterstützte ServusTV-Sendung in den API-Ergebnissen gefunden"
            }

            val result = ServusRefreshResult(
                episodes = episodes,
                refreshedAtMillis = refreshNow,
            )
            newsStore.save(result)

            val liveChannels = runCatching { refreshLiveChannels(market, refreshNow) }
                .onFailure { Log.w(TAG, "Live refresh failed (${it.javaClass.simpleName})") }
                .getOrElse { hubStore.loadLiveChannels() }
            if (liveChannels.isNotEmpty()) {
                hubStore.saveLiveChannels(liveChannels, refreshNow)
            }

            val catalogRefreshRequested = forceCatalog || shouldRefreshCatalog(refreshNow)
            var catalogRefreshSucceeded = false
            var categories = if (catalogRefreshRequested) {
                val cachedCategories = hubStore.loadCategories()
                try {
                    val outcome = refreshShowCatalog(market, refreshNow)
                    hubStore.saveCatalogDiagnostic(outcome.diagnostic)
                    catalogRefreshSucceeded = true
                    outcome.categories
                } catch (catalogError: ServusCatalogRefreshException) {
                    val diagnostic = catalogError.message ?: "Katalogfehler ohne Detail"
                    hubStore.saveCatalogDiagnostic(diagnostic)
                    Log.w(TAG, diagnostic)
                    if (forceCatalog) throw catalogError
                    cachedCategories
                }
            } else {
                hubStore.loadCategories()
            }

            var selectedShowsRefreshed = false
            if (!catalogRefreshSucceeded && currentSelectionStore.isConfigured() && categories.isNotEmpty()) {
                val targeted = refreshConfiguredAdditionalShows(market, categories, refreshNow)
                categories = targeted.categories
                selectedShowsRefreshed = targeted.changed
            }

            val beforeAvailabilityAnnotation = categories
            categories = annotateSelectedShowAvailability(
                categories = categories,
                observedAtMillis = refreshNow,
                detectNewItems = detectNewAvailability,
            )
            val selectedAvailabilityChanged = categories != beforeAvailabilityAnnotation

            if (catalogRefreshSucceeded) {
                hubStore.saveCatalog(categories, refreshNow)
            } else if (selectedShowsRefreshed || selectedAvailabilityChanged) {
                hubStore.saveCatalogContent(categories)
            }

            val selectedIds = currentSelectionStore.effectiveSelectedShowIds(categories)
            val selectedCatalogueEpisodes = categories
                .flatMap { it.shows }
                .filter { it.id in selectedIds }
                .flatMap { it.episodes }
            observedAvailabilityStore.finishSuccessfulRefresh(episodes + selectedCatalogueEpisodes)

            if (channelPublisher.isSupported()) {
                runCatching {
                    val contentChanged = previousEpisodes.map { ServusNewsPolicy.contentKey(it) to it.id } !=
                        episodes.map { ServusNewsPolicy.contentKey(it) to it.id }
                    val customCurrentChanged = currentSelectionStore.isConfigured() &&
                        (catalogRefreshSucceeded || selectedShowsRefreshed || selectedAvailabilityChanged)
                    if (contentChanged || customCurrentChanged || !channelPublisher.isPublished()) {
                        channelPublisher.publish(episodes)
                    }
                    if (liveChannels.isNotEmpty()) channelPublisher.publishLive(liveChannels)
                    if (catalogRefreshSucceeded && categories.isNotEmpty()) {
                        channelPublisher.publishShows(categories)
                    }
                }.onFailure { throwable ->
                    Log.w(TAG, "TvProvider sync skipped after refresh (${throwable.javaClass.simpleName})")
                }
            }
            result
        } catch (catalogError: ServusCatalogRefreshException) {
            throw catalogError
        } catch (throwable: Throwable) {
            newsStore.saveError(throwable.message ?: throwable.javaClass.simpleName)
            throw throwable
        }
    }

    private fun shouldRefreshCatalog(nowMillis: Long): Boolean {
        if (hubStore.loadCategories().isEmpty()) return true
        val last = hubStore.catalogLastSuccessMillis()
        return last <= 0L || nowMillis - last >= CATALOG_REFRESH_INTERVAL_MS
    }

    private suspend fun discoverCurrentCandidates(market: String): List<String> = coroutineScope {
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
            .take(MAX_CURRENT_COLLECTIONS)

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

    private suspend fun refreshShowCatalog(market: String, nowMillis: Long): CatalogRefreshOutcome = coroutineScope {
        val diagnostics = ServusCatalogDiagnosticBuilder()

        val landing = try {
            api.product(market, SHOWS_PRODUCT_ID)
        } catch (throwable: Throwable) {
            throw diagnostics.failure("Landing products/sendungen", throwable)
        }
        diagnostics.recordLanding(landing.collections)

        val categoryRefs = landing.collections
            .filter { it.listType != "reference" && !it.id.isNullOrBlank() }
        diagnostics.recordCategoryFilter(categoryRefs.size)
        if (categoryRefs.isEmpty()) {
            throw diagnostics.failure(
                stage = "Kategorien",
                detail = "0 verwertbare Collections nach aktuellem list_type/reference-Filter",
            )
        }

        val categoryLoads = try {
            categoryRefs.mapIndexed { order, ref ->
                async {
                    val collectionId = requireNotNull(ref.id)
                    runCatching {
                        val first = api.collection(market, collectionId, 0)
                        val cards = fetchCollectionCards(market, collectionId, first, MAX_CATEGORY_PAGES)
                        val showCards = cards.filter(ServusCatalogPolicy::isShowCard)
                        CategoryLoadResult(
                            seed = CategorySeed(
                                id = collectionId,
                                title = first.label?.takeIf { it.isNotBlank() }
                                    ?: ref.label?.takeIf { it.isNotBlank() }
                                    ?: "ServusTV",
                                order = order,
                                rawCardCount = cards.size,
                                cards = showCards,
                            ),
                        )
                    }.getOrElse { throwable ->
                        val httpError = throwable as? HttpException
                        if (httpError != null && ServusCatalogPolicy.canSkipCategoryHttpCode(httpError.code())) {
                            CategoryLoadResult(
                                skippedTitle = ref.label,
                                error = throwable,
                            )
                        } else {
                            throw throwable
                        }
                    }
                }
            }.awaitAll()
        } catch (throwable: Throwable) {
            throw diagnostics.failure("Kategorie-Collection", throwable)
        }

        categoryLoads.filter { it.error != null }.forEach { load ->
            diagnostics.recordSkippedCategory(load.skippedTitle, requireNotNull(load.error))
        }

        val seeds = categoryLoads.mapNotNull { it.seed }
            .filterNot { seed ->
                seed.title.startsWith("TV-Kanäle", true) || seed.title.startsWith("Live-Kanäle", true)
            }

        if (seeds.isEmpty()) {
            throw diagnostics.failure(
                stage = "Kategorie-Collection",
                detail = "Keine erreichbare Kategorie-Collection lieferte verwertbare Daten",
            )
        }

        seeds.forEach { seed ->
            diagnostics.recordCategory(seed.title, seed.rawCardCount, seed.cards.size)
        }

        val uniqueShowCards = LinkedHashMap<String, ServusCardDto>()
        seeds.forEach { seed ->
            seed.cards.forEach { card ->
                card.id?.let { uniqueShowCards.putIfAbsent(it, card) }
            }
        }
        diagnostics.recordUniqueShows(uniqueShowCards.size)
        if (uniqueShowCards.isEmpty()) {
            throw diagnostics.failure(
                stage = "Sendungsfilter",
                detail = "0 Sendungskarten nach aktuellem Filter (id+title, type=page, content_type!=film)",
            )
        }

        val semaphore = Semaphore(SHOW_PARALLELISM)
        val cores = try {
            uniqueShowCards.values.map { card ->
                async {
                    semaphore.withPermit {
                        loadShowCore(market, card, nowMillis)
                    }
                }
            }.awaitAll().filterNotNull().associateBy { it.id }
        } catch (throwable: Throwable) {
            throw diagnostics.failure("Sendungsdetails", throwable)
        }

        val categories = seeds.map { seed ->
            val shows = seed.cards.mapNotNull { card ->
                val id = card.id ?: return@mapNotNull null
                val core = cores[id] ?: return@mapNotNull null
                val episodes = core.episodes.map { episode ->
                    episode.copy(
                        categoryId = seed.id,
                        categoryTitle = seed.title,
                    )
                }
                ServusShow(
                    id = core.id,
                    title = core.title,
                    description = core.description,
                    categoryId = seed.id,
                    categoryTitle = seed.title,
                    artworkUri = core.artworkUri,
                    squareArtworkUri = core.squareArtworkUri,
                    logoUri = core.logoUri,
                    episodes = episodes,
                )
            }.distinctBy { it.id }
            ServusCategory(seed.id, seed.title, seed.order, shows)
        }.filter { it.shows.isNotEmpty() }

        val showCount = categories
            .flatMap { it.shows }
            .distinctBy { it.id }
            .size
        if (categories.isEmpty() || showCount == 0) {
            throw diagnostics.failure(
                stage = "Ergebnis",
                detail = "Katalog-Mapping ergab 0 Kategorien bzw. 0 Sendungen",
            )
        }

        CatalogRefreshOutcome(
            categories = categories,
            diagnostic = diagnostics.success(categories.size, showCount),
        )
    }

    private suspend fun refreshConfiguredAdditionalShows(
        market: String,
        categories: List<ServusCategory>,
        nowMillis: Long,
    ): SelectedShowRefreshOutcome = coroutineScope {
        val selectedIds = currentSelectionStore.effectiveSelectedShowIds(categories)
        val selectedShows = categories
            .flatMap { it.shows }
            .distinctBy { it.id }
            .filter { show ->
                show.id in selectedIds && !ServusCurrentChannelPolicy.isLegacyDefaultTitle(show.title)
            }
        if (selectedShows.isEmpty()) {
            return@coroutineScope SelectedShowRefreshOutcome(categories, changed = false)
        }

        val semaphore = Semaphore(SELECTED_SHOW_PARALLELISM)
        val refreshedById = selectedShows.map { show ->
            async {
                semaphore.withPermit {
                    val fallbackCard = ServusCardDto(
                        id = show.id,
                        type = "page",
                        title = show.title,
                        longDescription = show.description,
                    )
                    runCatching {
                        loadShowCore(
                            market = market,
                            card = fallbackCard,
                            nowMillis = nowMillis,
                            maxCollectionPages = MAX_SELECTED_SHOW_COLLECTION_PAGES,
                        )
                    }.getOrNull()?.let { show.id to it }
                }
            }
        }.awaitAll().filterNotNull().toMap()

        if (refreshedById.isEmpty()) {
            return@coroutineScope SelectedShowRefreshOutcome(categories, changed = false)
        }

        val updated = categories.map { category ->
            category.copy(
                shows = category.shows.map { show ->
                    val core = refreshedById[show.id] ?: return@map show
                    show.copy(
                        title = core.title,
                        description = core.description ?: show.description,
                        artworkUri = core.artworkUri ?: show.artworkUri,
                        squareArtworkUri = core.squareArtworkUri ?: show.squareArtworkUri,
                        logoUri = core.logoUri ?: show.logoUri,
                        episodes = ServusCatalogPolicy.selectChannelEpisodes(core.episodes + show.episodes),
                    )
                },
            )
        }
        SelectedShowRefreshOutcome(updated, changed = updated != categories)
    }

    private fun annotateSelectedShowAvailability(
        categories: List<ServusCategory>,
        observedAtMillis: Long,
        detectNewItems: Boolean,
    ): List<ServusCategory> {
        if (categories.isEmpty()) return categories
        val selectedIds = currentSelectionStore.effectiveSelectedShowIds(categories)
        if (selectedIds.isEmpty()) return categories

        return categories.map { category ->
            category.copy(
                shows = category.shows.map { show ->
                    if (show.id !in selectedIds || show.episodes.isEmpty()) return@map show
                    show.copy(
                        episodes = observedAvailabilityStore.annotateNewlyObserved(
                            episodes = show.episodes,
                            observedAtMillis = observedAtMillis,
                            detectNewItems = detectNewItems,
                        ),
                    )
                },
            )
        }
    }

    private suspend fun loadShowCore(
        market: String,
        card: ServusCardDto,
        nowMillis: Long,
        maxCollectionPages: Int = MAX_SHOW_COLLECTION_PAGES,
    ): ShowCore? {
        val id = card.id ?: return null
        val detail = runCatching { api.product(market, id) }.getOrNull()
        val title = detail?.title?.takeIf { !it.isNullOrBlank() }
            ?: card.title?.takeIf { it.isNotBlank() }
            ?: return null
        val resources = (detail?.mediaResources.orEmpty() + card.mediaResources).distinct()
        val logoUri = ServusCatalogPolicy.titleTreatment(id, resources)
        val collectionRefs = detail?.collections.orEmpty()
            .filter { it.listType != "reference" && !it.id.isNullOrBlank() }
            .take(MAX_SHOW_COLLECTIONS)

        val rawCards = coroutineScope {
            collectionRefs.map { ref ->
                async {
                    val collectionId = requireNotNull(ref.id)
                    val first = runCatching { api.collection(market, collectionId, 0) }.getOrNull()
                        ?: return@async emptyList()
                    fetchCollectionCards(market, collectionId, first, maxCollectionPages)
                }
            }.awaitAll().flatten()
        }

        val mapped = rawCards.mapNotNull { episodeCard ->
            ServusCatalogPolicy.toShowEpisode(
                card = episodeCard,
                showId = id,
                showTitle = title,
                categoryId = "",
                categoryTitle = "",
                showLogoUri = logoUri,
                nowMillis = nowMillis,
            )
        }
        val episodes = ServusCatalogPolicy.selectChannelEpisodes(mapped)
        return ShowCore(
            id = id,
            title = title,
            description = detail?.longDescription?.takeIf { it.isNotBlank() }
                ?: detail?.shortDescription?.takeIf { it.isNotBlank() }
                ?: card.longDescription?.takeIf { it.isNotBlank() }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            artworkUri = ServusCatalogPolicy.landscapeArtwork(id, resources),
            squareArtworkUri = ServusCatalogPolicy.squareArtwork(id, resources),
            logoUri = logoUri,
            episodes = episodes,
        )
    }

    private suspend fun refreshLiveChannels(market: String, nowMillis: Long): List<ServusLiveChannel> = coroutineScope {
        val first = api.collection(market, LIVE_COLLECTION_ID, 0)
        val cards = fetchCollectionCards(market, LIVE_COLLECTION_ID, first, MAX_LIVE_PAGES)
            .filter { !it.id.isNullOrBlank() && !it.title.isNullOrBlank() }
            .distinctBy { it.id }
        val semaphore = Semaphore(LIVE_GUIDE_PARALLELISM)
        cards.map { card ->
            async {
                semaphore.withPermit {
                    val id = requireNotNull(card.id)
                    val guide = runCatching { api.guide(market, id) }.getOrNull()
                    val programs = guide?.cards.orEmpty()
                        .mapNotNull(ServusCatalogPolicy::liveProgram)
                        .filter { it.endAtMillis > nowMillis - LIVE_GUIDE_PAST_GRACE_MS }
                        .sortedBy { it.startAtMillis }
                        .take(MAX_GUIDE_PROGRAMS)
                    ServusLiveChannel(
                        id = id,
                        title = requireNotNull(card.title),
                        description = card.longDescription?.takeIf { it.isNotBlank() }
                            ?: card.shortDescription?.takeIf { it.isNotBlank() },
                        artworkUri = ServusCatalogPolicy.landscapeArtwork(id, card.mediaResources),
                        squareArtworkUri = ServusCatalogPolicy.squareArtwork(id, card.mediaResources),
                        logoUri = ServusCatalogPolicy.titleTreatment(id, card.mediaResources),
                        programs = programs,
                    )
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchCollectionCards(
        market: String,
        collectionId: String,
        first: SearchResponseDto,
        maxPages: Int,
    ): List<ServusCardDto> {
        val cards = first.cards.toMutableList()
        var next = first.meta?.next
        var page = 1
        val seenOffsets = mutableSetOf(0)
        while (!next.isNullOrBlank() && page < maxPages) {
            val offset = ServusCatalogPolicy.nextOffset(next) ?: break
            if (!seenOffsets.add(offset)) break
            val response = runCatching { api.collection(market, collectionId, offset) }.getOrNull() ?: break
            cards += response.cards
            next = response.meta?.next
            page++
        }
        return cards
    }

    private data class CategorySeed(
        val id: String,
        val title: String,
        val order: Int,
        val rawCardCount: Int,
        val cards: List<ServusCardDto>,
    )

    private data class CategoryLoadResult(
        val seed: CategorySeed? = null,
        val skippedTitle: String? = null,
        val error: Throwable? = null,
    )

    private data class CatalogRefreshOutcome(
        val categories: List<ServusCategory>,
        val diagnostic: String,
    )

    private data class SelectedShowRefreshOutcome(
        val categories: List<ServusCategory>,
        val changed: Boolean,
    )

    private data class ShowCore(
        val id: String,
        val title: String,
        val description: String?,
        val artworkUri: String?,
        val squareArtworkUri: String?,
        val logoUri: String?,
        val episodes: List<ServusNewsEpisode>,
    )

    private companion object {
        const val TAG = "ServusRepository"
        const val SHOWS_PRODUCT_ID = "sendungen"
        const val LIVE_COLLECTION_ID = "6e6475bc-d2f2-4593-b95f-ed0a74206c62"
        const val CATALOG_REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
        const val LIVE_GUIDE_PAST_GRACE_MS = 30L * 60L * 1000L

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
        const val MAX_CURRENT_COLLECTIONS = 12
        const val MAX_DETAIL_CANDIDATES = 72
        const val MAX_CURRENT_EPISODES = 40

        const val SHOW_PARALLELISM = 4
        const val MAX_CATEGORY_PAGES = 20
        const val MAX_SHOW_COLLECTIONS = 5
        const val MAX_SHOW_COLLECTION_PAGES = 3
        const val SELECTED_SHOW_PARALLELISM = 2
        const val MAX_SELECTED_SHOW_COLLECTION_PAGES = 1
        const val MAX_LIVE_PAGES = 3
        const val LIVE_GUIDE_PARALLELISM = 4
        const val MAX_GUIDE_PROGRAMS = 8
    }
}
