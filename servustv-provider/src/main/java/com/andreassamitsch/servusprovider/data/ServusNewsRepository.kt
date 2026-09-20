package com.andreassamitsch.servusprovider.data

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusApi
import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusCollectionRefDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.andreassamitsch.servusprovider.tv.ServusChannelPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
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
    private val showChannelSelectionStore = ServusShowChannelSelectionStore(appContext)
    private val observedAvailabilityStore = ServusObservedAvailabilityStore(appContext)
    private val channelPublisher = ServusChannelPublisher(appContext)
    private val periodicShowRefreshStore = appContext.getSharedPreferences("servus_periodic_show_refresh", Context.MODE_PRIVATE)
    private val showEpisodeDetailSemaphore = Semaphore(SHOW_EPISODE_DETAIL_PARALLELISM)

    fun cachedEpisodes(): List<ServusNewsEpisode> = newsStore.loadEpisodes()
        .sortedWith(compareByDescending<ServusNewsEpisode> { ServusNewsPolicy.recencyMillis(it) ?: Long.MIN_VALUE })

    fun cachedCategories(): List<ServusCategory> = hubStore.loadCategories().sortedBy { it.order }
    fun cachedLiveChannels(): List<ServusLiveChannel> = hubStore.loadLiveChannels()
    fun cachedShow(showId: String): ServusShow? = hubStore.findShow(showId)

    fun setCurrentShowSelected(showId: String, selected: Boolean) {
        val categories = hubStore.loadCategories()
        if (selected) {
            categories.flatMap { it.shows }.firstOrNull { it.id == showId }
                ?.let { observedAvailabilityStore.baseline(it.episodes) }
        }
        currentSelectionStore.setSelected(showId, selected, categories)
    }

    fun isCurrentCollectionSelected(showId: String, collectionId: String): Boolean =
        currentSelectionStore.isCollectionSelected(showId, collectionId, hubStore.loadCategories())

    fun setCurrentCollectionSelected(showId: String, collectionId: String, selected: Boolean) {
        val categories = hubStore.loadCategories()
        if (selected) {
            hubStore.findShow(showId)?.collections
                ?.firstOrNull { it.id == collectionId }
                ?.let { observedAvailabilityStore.baseline(it.episodes) }
        }
        currentSelectionStore.setCollectionSelected(showId, collectionId, selected, categories)
    }

    fun isShowChannelSelected(showId: String): Boolean = showChannelSelectionStore.isSelected(showId)

    fun isCollectionChannelSelected(showId: String, collectionId: String): Boolean =
        showChannelSelectionStore.isCollectionSelected(showId, collectionId)

    fun selectedShowChannelIds(categories: List<ServusCategory> = hubStore.loadCategories()): Set<String> =
        showChannelSelectionStore.effectiveSelectedShowIds(categories)

    fun selectedChannelSourceCount(categories: List<ServusCategory> = hubStore.loadCategories()): Int =
        showChannelSelectionStore.effectiveSelectedSourceKeys(categories).size

    fun setShowChannelSelected(showId: String, selected: Boolean) {
        if (selected) hubStore.findShow(showId)?.let { observedAvailabilityStore.baseline(it.episodes) }
        showChannelSelectionStore.setSelected(showId, selected)
        if (!selected) {
            runCatching { channelPublisher.removeShowChannel(showId) }
                .onFailure { Log.w(TAG, "Immediate show-channel removal skipped (${it.javaClass.simpleName})") }
        }
    }

    fun setCollectionChannelSelected(showId: String, collectionId: String, selected: Boolean) {
        if (selected) {
            hubStore.findShow(showId)?.collections
                ?.firstOrNull { it.id == collectionId }
                ?.let { observedAvailabilityStore.baseline(it.episodes) }
        }
        showChannelSelectionStore.setCollectionSelected(showId, collectionId, selected)
        if (!selected) {
            runCatching { channelPublisher.removeCollectionChannel(showId, collectionId) }
                .onFailure { Log.w(TAG, "Immediate collection-channel removal skipped (${it.javaClass.simpleName})") }
        }
    }

    fun lastSuccessMillis(): Long = newsStore.lastSuccessMillis()
    fun catalogLastSuccessMillis(): Long = hubStore.catalogLastSuccessMillis()
    fun liveLastSuccessMillis(): Long = hubStore.liveLastSuccessMillis()
    fun catalogDiagnostic(): String? = hubStore.catalogDiagnostic()
    fun lastError(): String? = newsStore.lastError()
    fun tvChannelSupported(): Boolean = channelPublisher.isSupported()

    /**
     * Fast data (Aktuelles + live guide) is refreshed on every run. The complete show catalogue is
     * metadata-only and deliberately slower. Episode traffic is restricted to explicitly selected
     * show/collection sources; everything else is hydrated only when opened.
     */
    suspend fun refresh(forceCatalog: Boolean = false): ServusRefreshResult = supervisorScope {
        try {
            val previousEpisodes = newsStore.loadEpisodes()
            val session = sessionStore.get()
            val market = session.countryCode
            val refreshNow = System.currentTimeMillis()
            val detectNewAvailability = observedAvailabilityStore.isInitialized()

            val startedAt = SystemClock.elapsedRealtime()
            // Separate cache writes: a slow or failing section must not prevent the others.
            val currentDeferred = async {
                try {
                val discovery = discoverCurrentCandidates(market, previousEpisodes)
                val discoveryMs = SystemClock.elapsedRealtime() - startedAt
                val currentPlan = ServusCurrentRefreshPolicy.planCandidates(discovery.candidates, previousEpisodes)
                val earlyIds = discovery.earlyDetails.mapNotNull { it.card.id }.toSet()
                val remainingCandidates = currentPlan.candidatesToLoad.filterNot { it.id in earlyIds }
                val remainingDetails = fetchDetails(market, remainingCandidates)
                val hintsById = currentPlan.candidatesToLoad.associate { it.id to it.contentKindHint }
                val details = discovery.earlyDetails.map { early ->
                    early.copy(contentKindHint = early.card.id?.let(hintsById::get) ?: early.contentKindHint)
                } + remainingDetails
                val detailMs = SystemClock.elapsedRealtime() - startedAt - discoveryMs
                val mappedEpisodes = ServusNewsPolicy.deduplicateEpisodes(
                    currentPlan.cachedEpisodes + details.mapNotNull { detailed ->
                        ServusNewsPolicy.toSupportedEpisode(
                            card = detailed.card,
                            nowMillis = refreshNow,
                            contentKindHint = detailed.contentKindHint,
                        )
                    },
                ).take(MAX_CURRENT_EPISODES)
                val episodes = observedAvailabilityStore.annotateNewlyObserved(
                    episodes = mappedEpisodes,
                    observedAtMillis = refreshNow,
                    detectNewItems = detectNewAvailability,
                )
                check(episodes.isNotEmpty()) { "Keine unterstützte ServusTV-Sendung in den API-Ergebnissen gefunden" }
                val result = ServusRefreshResult(episodes, refreshNow)
                newsStore.save(result)
                Log.i(
                    TAG,
                    "Aktuelles refresh: discovery=${discoveryMs}ms, remainingDetails=${detailMs}ms, " +
                        "firstCache=${SystemClock.elapsedRealtime() - startedAt}ms, " +
                        "candidates=${discovery.candidates.size}, prefetched=${discovery.earlyDetails.size}, " +
                        "late=${remainingCandidates.size}, episodes=${episodes.size}",
                )

                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    newsStore.saveError(error.message ?: error.javaClass.simpleName)
                    Log.w(TAG, "Aktuelles refresh failed (${error.javaClass.simpleName}); other sections continue")
                    null
                }
            }
            val liveDeferred = async {
                try {
                    val fresh = refreshLiveChannels(market, refreshNow)
                    if (fresh.isNotEmpty()) hubStore.saveLiveChannels(fresh, refreshNow)
                    fresh.ifEmpty { hubStore.loadLiveChannels() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Live refresh failed (${error.javaClass.simpleName}); other sections continue")
                    hubStore.loadLiveChannels()
                }
            }
            val catalogDeferred = async {
                try {
                val catalogRefreshRequested = forceCatalog || shouldRefreshCatalog(refreshNow)
                var catalogRefreshSucceeded = false
                var categories = if (catalogRefreshRequested) {
                    val cachedCategories = hubStore.loadCategories()
                    try {
                        val outcome = refreshShowCatalog(market, cachedCategories)
                        hubStore.saveCatalogDiagnostic(outcome.diagnostic)
                        // Show the newly loaded catalogue before hydrating selected shows and TV channels.
                        hubStore.saveCatalog(outcome.categories, refreshNow)
                        Log.i(TAG, "Katalog cache: categories=${outcome.categories.size}, " +
                            "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms")
                        catalogRefreshSucceeded = true
                        outcome.categories
                    } catch (catalogError: ServusCatalogRefreshException) {
                        val diagnostic = catalogError.message ?: "Katalogfehler ohne Detail"
                        hubStore.saveCatalogDiagnostic(diagnostic)
                        Log.w(TAG, diagnostic)
                        cachedCategories
                    }
                } else {
                    hubStore.loadCategories()
                }

                    catalogRefreshSucceeded to categories
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    hubStore.saveCatalogDiagnostic("Katalogfehler: ${error.javaClass.simpleName}")
                    Log.w(TAG, "Catalog refresh failed (${error.javaClass.simpleName}); other sections continue")
                    false to hubStore.loadCategories()
                }
            }
            val currentResult = currentDeferred.await()
            val liveChannels = liveDeferred.await()
            val (catalogRefreshSucceeded, initialCategories) = catalogDeferred.await()
            val result = currentResult ?: ServusRefreshResult(previousEpisodes, newsStore.lastSuccessMillis())
            val episodes = result.episodes
            var categories = initialCategories

            val currentCollectionParents = currentSelectionStore.selectedCollectionParentShowIds(categories)
            val tvCollectionParents = showChannelSelectionStore.selectedCollectionParentShowIds(categories)
            val periodicShowIds = ServusShowRefreshPolicy.periodicShowIds(
                categories = categories,
                currentSelectionConfigured = currentSelectionStore.isConfigured(),
                currentSelectedIds = currentSelectionStore.effectiveSelectedShowIds(categories),
                tvChannelSelectedIds = showChannelSelectionStore.effectiveSelectedShowIds(categories),
                currentCollectionParentIds = currentCollectionParents,
                tvCollectionParentIds = tvCollectionParents,
            )
            // Only selected sources are periodically hydrated; manual refreshes may occur much
            // more frequently than the WorkManager interval and must not refetch every show.
            val dueShowIds = ServusShowRefreshSchedulePolicy.dueIds(periodicShowIds, refreshNow) { id ->
                periodicShowRefreshStore.getLong("success_$id", 0L)
            }
            var periodicShowsRefreshed = false
            var refreshedShowIds = emptySet<String>()
            if (categories.isNotEmpty() && dueShowIds.isNotEmpty()) {
                val targeted = refreshSubscribedShows(market, categories, refreshNow, dueShowIds)
                categories = targeted.categories
                periodicShowsRefreshed = targeted.changed
                refreshedShowIds = targeted.refreshedIds
                if (refreshedShowIds.isNotEmpty()) {
                    periodicShowRefreshStore.edit().apply {
                        refreshedShowIds.forEach { putLong("success_$it", refreshNow) }
                    }.apply()
                }
            }

            val beforeAvailabilityAnnotation = categories
            categories = annotateTrackedShowAvailability(
                categories = categories,
                trackedShowIds = refreshedShowIds,
                observedAtMillis = refreshNow,
                detectNewItems = detectNewAvailability,
            )
            val trackedAvailabilityChanged = categories != beforeAvailabilityAnnotation

            // The metadata snapshot is already visible. Write again only for actual
            // subscribed-show or availability changes, avoiding a redundant full UI redraw.
            if (periodicShowsRefreshed || trackedAvailabilityChanged) {
                hubStore.saveCatalogContent(categories)
            }

            val trackedCatalogueEpisodes = categories.flatMap { it.shows }
                .filter { it.id in refreshedShowIds }
                .flatMap { it.episodes }
            observedAvailabilityStore.finishSuccessfulRefresh(episodes + trackedCatalogueEpisodes)

            if (channelPublisher.isSupported()) {
                runCatching {
                    val contentChanged = previousEpisodes.map { ServusNewsPolicy.contentKey(it) to it.id } !=
                        episodes.map { ServusNewsPolicy.contentKey(it) to it.id }
                    val customCurrentChanged = currentSelectionStore.isConfigured() &&
                        (catalogRefreshSucceeded || periodicShowsRefreshed || trackedAvailabilityChanged)
                    if (contentChanged || customCurrentChanged || !channelPublisher.isPublished()) {
                        channelPublisher.publish(episodes)
                    }
                    if (liveChannels.isNotEmpty()) channelPublisher.publishLive(liveChannels)
                    val selectionChanged = showChannelSelectionStore.needsTvProviderSync()
                    if ((catalogRefreshSucceeded || periodicShowsRefreshed || selectionChanged) && categories.isNotEmpty()) {
                        channelPublisher.publishShows(categories)
                    }
                }.onFailure { Log.w(TAG, "TvProvider sync skipped after refresh (${it.javaClass.simpleName})") }
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

    /**
     * The exact ServusTV show-page API exposes current videos directly in its editorial
     * collections. Prefer it over repeated text searches. Keep the original search discovery as
     * a full fallback if either of the two previously supported shows becomes unavailable.
     */
    private suspend fun discoverCurrentCandidates(
        market: String,
        previousEpisodes: List<ServusNewsEpisode>,
    ): CurrentDiscoveryResult {
        val direct = try {
            discoverDirectCurrentCandidates(market)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Aktuelles direct source unavailable (${error.javaClass.simpleName}); search fallback")
            null
        }
        if (direct != null) {
            Log.i(TAG, "Aktuelles source=direct, candidates=${direct.size}")
            return CurrentDiscoveryResult(direct, emptyList())
        }
        Log.i(TAG, "Aktuelles source=search-fallback")
        return discoverSearchCurrentCandidates(market, previousEpisodes)
    }

    private suspend fun discoverDirectCurrentCandidates(market: String): List<ServusCurrentCandidate> = coroutineScope {
        val collectionSemaphore = Semaphore(DIRECT_COLLECTION_PARALLELISM)
        val roots = listOf(ServusBranding.NEWS_SHOW_ID, WEGSCHEIDER_SHOW_ID)
        val byShow = roots.map { rootId ->
            async {
                val product = api.product(market, rootId)
                check(product.id == rootId) { "Direct Aktuelles show missing: $rootId" }
                val collectionIds = product.collections.mapNotNull { it.id?.takeIf(String::isNotBlank) }
                    .distinct().take(MAX_DIRECT_COLLECTIONS_PER_SHOW)
                check(collectionIds.isNotEmpty()) { "No collections for direct Aktuelles show: $rootId" }
                collectionIds.map { collectionId ->
                    async {
                        collectionSemaphore.withPermit {
                            val response = api.collection(market, collectionId, 0)
                            ServusDirectCurrentPolicy.candidatesForCollection(rootId, response.label, response.cards)
                        }
                    }
                }.awaitAll().flatten()
            }
        }.awaitAll()
        val news = byShow[0]
        val commentary = byShow[1]
        // A partial API response must not silently replace the previously available three formats.
        check(news.any { it.contentKindHint == ServusContentKind.NEWS_90_SECONDS } &&
            news.any { it.contentKindHint == ServusContentKind.FULL_NEWS } &&
            commentary.any { it.contentKindHint == ServusContentKind.WEGSCHEIDER }) {
            "Direct Aktuelles collections are incomplete"
        }
        mergeCurrentCandidates(news + commentary).take(MAX_DETAIL_CANDIDATES)
    }

    private suspend fun discoverSearchCurrentCandidates(
        market: String,
        previousEpisodes: List<ServusNewsEpisode>,
    ): CurrentDiscoveryResult = coroutineScope {
        val searchStartedAt = SystemClock.elapsedRealtime()
        val responseGroups = SEARCH_QUERIES.map { query -> async { fetchCurrentSearchPages(market, query) } }.awaitAll()
        val directCards = responseGroups.flatten().flatMap { it.cards }
        val directCandidates = responseGroups.flatMap { responses ->
            responses.flatMap { it.cards }
                .filter(ServusNewsPolicy::couldBelongToSupportedContent)
                .mapNotNull { card -> card.id?.let { ServusCurrentCandidate(it, ServusNewsPolicy.contentKind(card)) } }
                .distinctBy { it.id }
                .take(MAX_DIRECT_IDS_PER_QUERY)
        }
        val searchMs = SystemClock.elapsedRealtime() - searchStartedAt
        // Overlap a small number of new direct video products with collection discovery. Avoid
        // prefetching page products: their collections are already requested by the discovery.
        val pageIds = directCards.asSequence().filter { it.type == "page" }.mapNotNull { it.id }.toSet()
        val earlyCandidates = ServusCurrentRefreshPolicy.planCandidates(
            directCandidates, previousEpisodes,
        ).candidatesToLoad.filterNot { it.id in pageIds }.take(MAX_DIRECT_PREFETCH_CANDIDATES)
        val earlyDetails = async {
            fetchDetails(market, earlyCandidates, parallelism = DIRECT_PREFETCH_PARALLELISM)
        }
        val contentPages = directCards.filter { it.type == "page" && ServusNewsPolicy.couldBelongToSupportedContent(it) }
            .distinctBy { it.id }
        val collectionSources = contentPages.take(MAX_CONTENT_PAGES).map { page ->
            async {
                val pageId = page.id ?: return@async emptyList()
                val product = runCatching { api.product(market, pageId) }.getOrNull() ?: return@async emptyList()
                val ownerTitle = product.title?.takeIf { it.isNotBlank() } ?: page.title?.takeIf { it.isNotBlank() }
                product.collections.mapNotNull { ref ->
                    ref.id?.let { CurrentCollectionSource(it, ref.label, pageId, ownerTitle) }
                }
            }
        }.awaitAll().flatten().distinctBy { it.collectionId }.take(MAX_CURRENT_COLLECTIONS)

        val collectionCandidates = collectionSources.map { source ->
            async {
                val response = runCatching { api.collection(market, source.collectionId, 0) }.getOrNull()
                    ?: return@async emptyList()
                if (ServusCollectionPolicy.classify(
                        response,
                        source.ownerShowId.orEmpty(),
                        source.ownerShowTitle.orEmpty(),
                    ) != ServusCollectionRole.CONTENT
                ) return@async emptyList()
                val label = response.label?.takeIf { it.isNotBlank() } ?: source.referenceLabel
                val collectionHint = ServusCatalogPolicy.contentKindForCollection(
                    source.ownerShowId,
                    source.ownerShowTitle,
                    label,
                )
                response.cards.mapNotNull { card ->
                    val id = card.id ?: return@mapNotNull null
                    val hint = collectionHint ?: ServusNewsPolicy.contentKind(card)
                    if (hint != null || ServusNewsPolicy.couldBelongToSupportedContent(card)) {
                        ServusCurrentCandidate(id, hint)
                    } else null
                }
            }
        }.awaitAll().flatten()

        val candidates = mergeCurrentCandidates(directCandidates + collectionCandidates).take(MAX_DETAIL_CANDIDATES)
        val details = earlyDetails.await()
        Log.i(
            TAG,
            "Aktuelles discovery: search=${searchMs}ms, collections+prefetch=" +
                "${SystemClock.elapsedRealtime() - searchStartedAt - searchMs}ms, " +
                "direct=${directCandidates.size}, collections=${collectionCandidates.size}, " +
                "early=${details.size}",
        )
        CurrentDiscoveryResult(candidates, details)
    }

    private fun mergeCurrentCandidates(candidates: List<ServusCurrentCandidate>): List<ServusCurrentCandidate> {
        val merged = LinkedHashMap<String, ServusCurrentCandidate>()
        candidates.forEach { candidate ->
            if (candidate.id.isBlank()) return@forEach
            val existing = merged[candidate.id]
            if (existing == null || existing.contentKindHint == null && candidate.contentKindHint != null) {
                merged[candidate.id] = candidate
            }
        }
        return merged.values.toList()
    }

    private suspend fun fetchCurrentSearchPages(market: String, query: String): List<SearchResponseDto> {
        val responses = mutableListOf<SearchResponseDto>()
        val seenOffsets = mutableSetOf(0)
        var response = api.search(market, query, 0)
        responses += response
        var next = response.meta?.next
        var page = 1
        while (!next.isNullOrBlank() && page < MAX_CURRENT_SEARCH_PAGES) {
            val offset = ServusCatalogPolicy.nextOffset(next) ?: break
            if (!seenOffsets.add(offset)) break
            response = api.search(market, query, offset)
            responses += response
            next = response.meta?.next
            page++
        }
        return responses
    }

    private suspend fun fetchDetails(
        market: String,
        candidates: List<ServusCurrentCandidate>,
        parallelism: Int = DETAIL_PARALLELISM,
    ): List<HydratedCurrentCandidate> = coroutineScope {
        val semaphore = Semaphore(parallelism)
        candidates.map { candidate ->
            async {
                semaphore.withPermit {
                    runCatching { api.product(market, candidate.id) }.getOrNull()
                        ?.let { HydratedCurrentCandidate(it, candidate.contentKindHint) }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Complete catalogue refresh intentionally stays metadata-only. */
    private suspend fun refreshShowCatalog(
        market: String,
        cachedCategories: List<ServusCategory>,
    ): CatalogRefreshOutcome = coroutineScope {
        val diagnostics = ServusCatalogDiagnosticBuilder()
        val landing = try {
            api.product(market, SHOWS_PRODUCT_ID)
        } catch (throwable: Throwable) {
            throw diagnostics.failure("Landing products/sendungen", throwable)
        }
        diagnostics.recordLanding(landing.collections)
        val categoryRefs = landing.collections.filter { it.listType != "reference" && !it.id.isNullOrBlank() }
        diagnostics.recordCategoryFilter(categoryRefs.size)
        if (categoryRefs.isEmpty()) throw diagnostics.failure("Kategorien", "0 verwertbare Collections")

        // A cold cache can show the first useful category without waiting for every
        // category's remaining pages. The regular complete snapshot always replaces it.
        val firstCategoryPublished = AtomicBoolean(false)
        // Bound category pagination instead of issuing every category request at once.
        val categorySemaphore = Semaphore(CATALOG_CATEGORY_PARALLELISM)
        val categoryLoads = try {
            categoryRefs.mapIndexed { order, ref ->
                async {
                    val collectionId = requireNotNull(ref.id)
                    runCatching {
                        val (first, cards) = categorySemaphore.withPermit {
                            val first = api.collection(market, collectionId, 0)
                            if (cachedCategories.isEmpty() && !firstCategoryPublished.get()) {
                                val initialShows = first.cards.filter(ServusCatalogPolicy::isShowCard)
                                    .mapNotNull { card ->
                                        val core = showMetadataFromCard(card, null) ?: return@mapNotNull null
                                        val categoryTitle = first.label?.takeIf { it.isNotBlank() }
                                            ?: ref.label?.takeIf { it.isNotBlank() } ?: "ServusTV"
                                        ServusShow(
                                            id = core.id,
                                            title = core.title,
                                            description = core.description,
                                            categoryId = collectionId,
                                            categoryTitle = categoryTitle,
                                            artworkUri = core.artworkUri,
                                            squareArtworkUri = core.squareArtworkUri,
                                            logoUri = core.logoUri,
                                            episodes = core.episodes,
                                            collections = core.collections,
                                        )
                                    }.distinctBy { it.id }
                                if (initialShows.isNotEmpty() && firstCategoryPublished.compareAndSet(false, true)) {
                                    hubStore.saveCatalogContent(listOf(ServusCategory(
                                        collectionId, initialShows.first().categoryTitle, order, initialShows,
                                    )))
                                    Log.i(TAG, "Katalog preview: first category displayed from first collection page")
                                }
                            }
                            first to fetchCollectionCards(market, collectionId, first, MAX_CATEGORY_PAGES)
                        }
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
                            CategoryLoadResult(skippedTitle = ref.label, error = throwable)
                        } else throw throwable
                    }
                }
            }.awaitAll()
        } catch (throwable: Throwable) {
            throw diagnostics.failure("Kategorie-Collection", throwable)
        }
        categoryLoads.filter { it.error != null }.forEach {
            diagnostics.recordSkippedCategory(it.skippedTitle, requireNotNull(it.error))
        }
        val seeds = categoryLoads.mapNotNull { it.seed }
            .filterNot { it.title.startsWith("TV-Kanäle", true) || it.title.startsWith("Live-Kanäle", true) }
        if (seeds.isEmpty()) throw diagnostics.failure("Kategorie-Collection", "Keine erreichbare Kategorie")
        seeds.forEach { diagnostics.recordCategory(it.title, it.rawCardCount, it.cards.size) }

        val uniqueShowCards = LinkedHashMap<String, ServusCardDto>()
        seeds.forEach { seed -> seed.cards.forEach { card -> card.id?.let { uniqueShowCards.putIfAbsent(it, card) } } }
        diagnostics.recordUniqueShows(uniqueShowCards.size)
        if (uniqueShowCards.isEmpty()) throw diagnostics.failure("Sendungsfilter", "0 Sendungskarten")

        val cachedShowsById = cachedCategories.flatMap { it.shows }.distinctBy { it.id }.associateBy { it.id }
        val cores = uniqueShowCards.values.mapNotNull { card ->
            showMetadataFromCard(card, card.id?.let(cachedShowsById::get))
        }.associateBy { it.id }

        val categories = seeds.map { seed ->
            val shows = seed.cards.mapNotNull { card ->
                val core = cores[card.id] ?: return@mapNotNull null
                ServusShow(
                    id = core.id,
                    title = core.title,
                    description = core.description,
                    categoryId = seed.id,
                    categoryTitle = seed.title,
                    artworkUri = core.artworkUri,
                    squareArtworkUri = core.squareArtworkUri,
                    logoUri = ServusBranding.logoUriForShow(core.id, core.logoUri),
                    episodes = core.episodes.map { episode ->
                        episode.copy(categoryId = seed.id, categoryTitle = seed.title)
                    },
                    collections = core.collections,
                )
            }.distinctBy { it.id }
            ServusCategory(seed.id, seed.title, seed.order, shows)
        }.filter { it.shows.isNotEmpty() }

        val showCount = categories.flatMap { it.shows }.distinctBy { it.id }.size
        if (categories.isEmpty() || showCount == 0) throw diagnostics.failure("Ergebnis", "0 Kategorien/Sendungen")
        CatalogRefreshOutcome(categories, diagnostics.success(categories.size, showCount))
    }

    private suspend fun refreshSubscribedShows(
        market: String,
        categories: List<ServusCategory>,
        nowMillis: Long,
        showIds: Set<String>,
    ): SubscribedShowRefreshOutcome = coroutineScope {
        val selectedShows = categories.flatMap { it.shows }.distinctBy { it.id }.filter { it.id in showIds }
        if (selectedShows.isEmpty()) return@coroutineScope SubscribedShowRefreshOutcome(categories, false)

        val semaphore = Semaphore(SUBSCRIBED_SHOW_PARALLELISM)
        val refreshedById = selectedShows.map { show ->
            async {
                semaphore.withPermit {
                    val currentCollectionIds = currentSelectionStore.selectedCollectionIdsForShow(show.id, categories)
                    val tvCollectionIds = showChannelSelectionStore.selectedCollectionIdsForShow(show.id, categories)
                    val selectedCollectionIds = currentCollectionIds + tvCollectionIds
                    val wholeShowSelected = currentSelectionStore.isSelected(show, categories) ||
                        showChannelSelectionStore.isSelected(show.id)
                    val knownContentIds = show.collections
                        .filter { it.role == ServusCollectionRole.CONTENT }
                        .mapTo(linkedSetOf()) { it.id }
                    val requestedIds = when {
                        !wholeShowSelected && selectedCollectionIds.isNotEmpty() -> selectedCollectionIds
                        wholeShowSelected && knownContentIds.isNotEmpty() -> knownContentIds
                        else -> null
                    }
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
                            maxCollectionPages = MAX_SUBSCRIBED_SHOW_COLLECTION_PAGES,
                            cachedShow = show,
                            requestedCollectionIds = requestedIds,
                        )
                    }.getOrNull()?.let { show.id to it }
                }
            }
        }.awaitAll().filterNotNull().toMap()
        if (refreshedById.isEmpty()) return@coroutineScope SubscribedShowRefreshOutcome(categories, false)

        val updated = categories.map { category ->
            category.copy(
                shows = category.shows.map { show ->
                    val core = refreshedById[show.id] ?: return@map show
                    show.copy(
                        title = core.title,
                        description = core.description ?: show.description,
                        artworkUri = core.artworkUri ?: show.artworkUri,
                        squareArtworkUri = core.squareArtworkUri ?: show.squareArtworkUri,
                        logoUri = ServusBranding.logoUriForShow(show.id, core.logoUri ?: show.logoUri),
                        episodes = ServusCatalogPolicy.selectChannelEpisodes(core.episodes + show.episodes)
                            .map(ServusBranding::canonicalizeEpisode),
                        collections = core.collections,
                    )
                },
            )
        }
        SubscribedShowRefreshOutcome(updated, updated != categories, refreshedById.keys)
    }

    private fun annotateTrackedShowAvailability(
        categories: List<ServusCategory>,
        trackedShowIds: Set<String>,
        observedAtMillis: Long,
        detectNewItems: Boolean,
    ): List<ServusCategory> {
        if (categories.isEmpty() || trackedShowIds.isEmpty()) return categories
        return categories.map { category ->
            category.copy(shows = category.shows.map { show ->
                if (show.id !in trackedShowIds || show.episodes.isEmpty()) return@map show
                val annotated = observedAvailabilityStore.annotateNewlyObserved(
                    show.episodes,
                    observedAtMillis,
                    detectNewItems,
                )
                show.copy(
                    episodes = annotated,
                    collections = show.collections.map { collection ->
                        collection.copy(
                            episodes = annotated.filter { it.sourceCollectionId == collection.id },
                        )
                    },
                )
            })
        }
    }

    suspend fun refreshShow(showId: String): ServusShow? {
        val categories = hubStore.loadCategories()
        val cachedShow = categories.flatMap { it.shows }.firstOrNull { it.id == showId } ?: return null
        val session = sessionStore.get()
        val nowMillis = System.currentTimeMillis()
        val core = loadShowCore(
            market = session.countryCode,
            card = ServusCardDto(id = cachedShow.id, type = "page", title = cachedShow.title, longDescription = cachedShow.description),
            nowMillis = nowMillis,
            maxCollectionPages = MAX_SHOW_COLLECTION_PAGES,
            cachedShow = cachedShow,
            requestedCollectionIds = null,
        ) ?: return cachedShow
        val annotatedEpisodes = observedAvailabilityStore.annotateNewlyObserved(
            core.episodes.map(ServusBranding::canonicalizeEpisode),
            nowMillis,
            observedAvailabilityStore.isInitialized(),
        )
        val updatedCategories = categories.map { category ->
            category.copy(shows = category.shows.map { show ->
                if (show.id != showId) return@map show
                show.copy(
                    title = core.title,
                    description = core.description ?: show.description,
                    artworkUri = core.artworkUri ?: show.artworkUri,
                    squareArtworkUri = core.squareArtworkUri ?: show.squareArtworkUri,
                    logoUri = ServusBranding.logoUriForShow(show.id, core.logoUri ?: show.logoUri),
                    episodes = annotatedEpisodes.map { it.copy(categoryId = category.id, categoryTitle = category.title) },
                    collections = core.collections.map { collection ->
                        collection.copy(
                            episodes = annotatedEpisodes.filter { it.sourceCollectionId == collection.id },
                        )
                    },
                )
            })
        }
        hubStore.saveCatalogContent(updatedCategories)
        observedAvailabilityStore.finishSuccessfulRefresh(annotatedEpisodes)
        val refreshedShow = updatedCategories.flatMap { it.shows }.firstOrNull { it.id == showId }
        if (channelPublisher.isSupported() && refreshedShow != null) {
            runCatching {
                if (currentSelectionStore.isSelected(refreshedShow, updatedCategories)) channelPublisher.publish(newsStore.loadEpisodes())
                if (showChannelSelectionStore.isSelected(showId) ||
                    showChannelSelectionStore.selectedCollectionIdsForShow(showId, updatedCategories).isNotEmpty()
                ) channelPublisher.publishShows(updatedCategories)
            }.onFailure { Log.w(TAG, "TvProvider sync skipped after show refresh (${it.javaClass.simpleName})") }
        }
        return refreshedShow
    }

    private fun showMetadataFromCard(card: ServusCardDto, cachedShow: ServusShow?): ShowCore? {
        val id = card.id ?: return null
        val title = card.title?.takeIf { it.isNotBlank() } ?: cachedShow?.title?.takeIf { it.isNotBlank() } ?: return null
        return ShowCore(
            id = id,
            title = title,
            description = card.longDescription?.takeIf { it.isNotBlank() }
                ?: card.shortDescription?.takeIf { it.isNotBlank() }
                ?: cachedShow?.description,
            artworkUri = ServusCatalogPolicy.landscapeArtwork(id, card.mediaResources) ?: cachedShow?.artworkUri,
            squareArtworkUri = ServusCatalogPolicy.squareArtwork(id, card.mediaResources) ?: cachedShow?.squareArtworkUri,
            logoUri = ServusBranding.logoUriForShow(
                id,
                ServusCatalogPolicy.titleTreatment(id, card.mediaResources) ?: cachedShow?.logoUri,
            ),
            episodes = cachedShow?.episodes.orEmpty().map(ServusBranding::canonicalizeEpisode),
            collections = cachedShow?.collections.orEmpty(),
        )
    }

    /**
     * Refreshes one show root. If requestedCollectionIds is non-null, only those already-selected
     * collections are fetched; this is the normal periodic path. A null set is reserved for opening
     * or initially discovering a show and may inspect every advertised first page once.
     */
    private suspend fun loadShowCore(
        market: String,
        card: ServusCardDto,
        nowMillis: Long,
        maxCollectionPages: Int = MAX_SHOW_COLLECTION_PAGES,
        cachedShow: ServusShow? = null,
        requestedCollectionIds: Set<String>? = null,
    ): ShowCore? {
        val id = card.id ?: return null
        val detail = runCatching { api.product(market, id) }.getOrNull()
        val title = detail?.title?.takeIf { !it.isNullOrBlank() } ?: card.title?.takeIf { it.isNotBlank() } ?: return null
        val resources = ServusCatalogPolicy.mergeMediaResources(card.mediaResources, detail?.mediaResources.orEmpty())
        val logoUri = ServusBranding.logoUriForShow(id, ServusCatalogPolicy.titleTreatment(id, resources))

        val detailRefs = detail?.collections.orEmpty().filter { !it.id.isNullOrBlank() }
        val refs = if (requestedCollectionIds == null) {
            detailRefs.take(MAX_SHOW_COLLECTIONS)
        } else {
            val byId = detailRefs.associateBy { it.id }
            requestedCollectionIds.take(MAX_SHOW_COLLECTIONS).map { collectionId ->
                byId[collectionId] ?: ServusCollectionRefDto(id = collectionId)
            }
        }

        val collectionLoads = coroutineScope {
            refs.map { ref ->
                async {
                    val collectionId = requireNotNull(ref.id)
                    val first = runCatching { api.collection(market, collectionId, 0) }.getOrNull()
                        ?: return@async null
                    val role = ServusCollectionPolicy.classify(first, id, title)
                    val collectionTitle = ServusCollectionPolicy.displayTitle(first, ref.label)
                    val contentShowId = ServusCollectionPolicy.contentShowId(id, first)
                    val contentShowTitle = ServusCollectionPolicy.contentShowTitle(title, first)
                    val cards = if (role == ServusCollectionRole.CONTENT) {
                        fetchCollectionCards(market, collectionId, first, maxCollectionPages)
                    } else {
                        emptyList()
                    }
                    LoadedCollection(
                        collection = ServusShowCollection(
                            id = collectionId,
                            title = collectionTitle,
                            listType = first.listType,
                            type = first.type,
                            role = role,
                            contentShowId = contentShowId,
                            contentShowTitle = contentShowTitle,
                            episodes = cachedShow?.collections?.firstOrNull { it.id == collectionId }?.episodes.orEmpty(),
                        ),
                        cards = cards.map { episodeCard ->
                            ServusSourcedCard(
                                card = episodeCard,
                                sourceCollectionId = collectionId,
                                sourceCollectionLabel = collectionTitle,
                                contentKindHint = ServusCatalogPolicy.contentKindForCollection(
                                    contentShowId,
                                    contentShowTitle,
                                    collectionTitle,
                                ) ?: ServusNewsPolicy.contentKind(episodeCard),
                                contentShowId = contentShowId,
                                contentShowTitle = contentShowTitle,
                            )
                        },
                    )
                }
            }.awaitAll().filterNotNull()
        }

        val sourcedCards = collectionLoads.flatMap { it.cards }
        val candidateCards = sourcedCards
            .asSequence()
            .filter { it.card.id?.isNotBlank() == true && it.card.playable != false }
            .distinctBy { it.card.id }
            .take(MAX_SHOW_EPISODE_DETAIL_CANDIDATES)
            .toList()
        val hydratedCards = hydrateShowEpisodeCards(
            market = market,
            candidates = candidateCards,
            cachedEpisodes = cachedShow?.episodes.orEmpty(),
        )
        val cachedById = cachedShow?.episodes.orEmpty().associateBy { it.id }
        val mapped = hydratedCards.mapNotNull { hydrated ->
            val episodeCard = hydrated.candidate.card
            val effectiveParentId = parentShowIdFromProduct(episodeCard) ?: hydrated.candidate.contentShowId ?: id
            val effectiveTitle = hydrated.candidate.contentShowTitle ?: title
            val candidate = hydrated.candidate.copy(contentShowId = effectiveParentId, contentShowTitle = effectiveTitle)
            val cachedEpisode = episodeCard.id?.let(cachedById::get)
            val fresh = ServusCatalogPolicy.toShowEpisode(
                candidate = candidate,
                showId = effectiveParentId,
                showTitle = effectiveTitle,
                categoryId = "",
                categoryTitle = "",
                showLogoUri = if (effectiveParentId == id) logoUri else ServusBranding.logoUriForShow(effectiveParentId, null),
                nowMillis = nowMillis,
            )
            when {
                fresh != null -> fresh.copy(
                    publishedAtMillis = fresh.publishedAtMillis ?: cachedEpisode?.publishedAtMillis,
                )
                !hydrated.productDetailLoaded && cachedEpisode != null -> cachedEpisode
                else -> null
            }
        }.map(ServusBranding::canonicalizeEpisode)

        val mergedEpisodes = ServusCatalogPolicy.selectChannelEpisodes(mapped + cachedShow?.episodes.orEmpty())
        val loadedCollectionsById = collectionLoads.associate { load ->
            load.collection.id to load.collection.copy(
                episodes = mergedEpisodes.filter { it.sourceCollectionId == load.collection.id },
            )
        }
        val mergedCollections = buildList {
            cachedShow?.collections.orEmpty().forEach { cached ->
                add(loadedCollectionsById[cached.id] ?: cached)
            }
            collectionLoads.forEach { load ->
                if (none { it.id == load.collection.id }) add(requireNotNull(loadedCollectionsById[load.collection.id]))
            }
        }

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
            episodes = mergedEpisodes,
            collections = mergedCollections,
        )
    }

    private suspend fun hydrateShowEpisodeCards(
        market: String,
        candidates: List<ServusSourcedCard>,
        cachedEpisodes: List<ServusNewsEpisode>,
    ): List<HydratedShowEpisodeCard> = coroutineScope {
        val cachedById = cachedEpisodes.associateBy { it.id }
        candidates.map { candidate ->
            async {
                val card = candidate.card
                val id = card.id ?: return@async HydratedShowEpisodeCard(candidate, false)
                val cached = cachedById[id]
                val hasReusableSourceTime = !card.sunriseTimestamp.isNullOrBlank() || cached?.publishedAtMillis != null
                val hasDescription = !card.longDescription.isNullOrBlank() || !card.shortDescription.isNullOrBlank()
                val hasParent = candidate.contentShowId != null || !card.deeplinkPlaylist.isNullOrBlank() || !card.nextPlaylist.isNullOrBlank()
                if (hasReusableSourceTime && hasDescription && hasParent && canMapShowEpisodeWithoutProduct(card)) {
                    HydratedShowEpisodeCard(candidate, false)
                } else {
                    showEpisodeDetailSemaphore.withPermit {
                        val detail = runCatching { api.product(market, id) }.getOrNull()
                        if (detail == null) HydratedShowEpisodeCard(candidate, false)
                        else HydratedShowEpisodeCard(
                            candidate.copy(card = ServusCatalogPolicy.mergeEpisodeProduct(card, detail)),
                            true,
                        )
                    }
                }
            }
        }.awaitAll()
    }

    private fun canMapShowEpisodeWithoutProduct(card: ServusCardDto): Boolean =
        card.duration?.let { it > 0L } == true &&
            card.playable != false &&
            (card.type == "video" || card.contentType == "film" || card.contentType == "episode" || card.contentType == "clip")

    private fun parentShowIdFromProduct(card: ServusCardDto): String? = sequenceOf(card.deeplinkPlaylist, card.nextPlaylist)
        .filterNotNull()
        .mapNotNull { it.takeIf { value -> value.endsWith(ALL_EPISODES_SUFFIX) }?.removeSuffix(ALL_EPISODES_SUFFIX) }
        .firstOrNull { it.isNotBlank() }

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

    private data class CurrentCollectionSource(
        val collectionId: String,
        val referenceLabel: String?,
        val ownerShowId: String?,
        val ownerShowTitle: String?,
    )
    private data class HydratedCurrentCandidate(val card: ServusCardDto, val contentKindHint: ServusContentKind?)
    private data class CurrentDiscoveryResult(
        val candidates: List<ServusCurrentCandidate>,
        val earlyDetails: List<HydratedCurrentCandidate>,
    )
    private data class CategorySeed(val id: String, val title: String, val order: Int, val rawCardCount: Int, val cards: List<ServusCardDto>)
    private data class CategoryLoadResult(val seed: CategorySeed? = null, val skippedTitle: String? = null, val error: Throwable? = null)
    private data class CatalogRefreshOutcome(val categories: List<ServusCategory>, val diagnostic: String)
    private data class SubscribedShowRefreshOutcome(val categories: List<ServusCategory>, val changed: Boolean, val refreshedIds: Set<String> = emptySet())
    private data class HydratedShowEpisodeCard(val candidate: ServusSourcedCard, val productDetailLoaded: Boolean)
    private data class LoadedCollection(val collection: ServusShowCollection, val cards: List<ServusSourcedCard>)
    private data class ShowCore(
        val id: String,
        val title: String,
        val description: String?,
        val artworkUri: String?,
        val squareArtworkUri: String?,
        val logoUri: String?,
        val episodes: List<ServusNewsEpisode>,
        val collections: List<ServusShowCollection>,
    )

    private companion object {
        const val TAG = "ServusRepository"
        const val SHOWS_PRODUCT_ID = "sendungen"
        const val LIVE_COLLECTION_ID = "6e6475bc-d2f2-4593-b95f-ed0a74206c62"
        const val CATALOG_REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
        const val LIVE_GUIDE_PAST_GRACE_MS = 30L * 60L * 1000L
        const val ALL_EPISODES_SUFFIX = ":all_episodes"
        val SEARCH_QUERIES = listOf("Servus Nachrichten", "Nachrichten 19:20", "Servus Nachrichten in 90 Sekunden", "Der Wegscheider")
        const val MAX_CURRENT_SEARCH_PAGES = 3
        const val MAX_DIRECT_IDS_PER_QUERY = 16
        const val DETAIL_PARALLELISM = 6
        const val MAX_DIRECT_PREFETCH_CANDIDATES = 8
        const val DIRECT_PREFETCH_PARALLELISM = 2
        const val WEGSCHEIDER_SHOW_ID = "AA-1Q66UK71N1W11"
        const val DIRECT_COLLECTION_PARALLELISM = 4
        const val MAX_DIRECT_COLLECTIONS_PER_SHOW = 8
        const val MAX_CONTENT_PAGES = 8
        const val MAX_CURRENT_COLLECTIONS = 12
        const val MAX_DETAIL_CANDIDATES = 72
        const val MAX_CURRENT_EPISODES = 40
        const val SHOW_EPISODE_DETAIL_PARALLELISM = 6
        const val MAX_SHOW_EPISODE_DETAIL_CANDIDATES = 20
        const val MAX_CATEGORY_PAGES = 20
        const val CATALOG_CATEGORY_PARALLELISM = 4
        const val MAX_SHOW_COLLECTIONS = 8
        const val MAX_SHOW_COLLECTION_PAGES = 3
        const val SUBSCRIBED_SHOW_PARALLELISM = 2
        const val MAX_SUBSCRIBED_SHOW_COLLECTION_PAGES = 1
        const val MAX_LIVE_PAGES = 3
        const val LIVE_GUIDE_PARALLELISM = 4
        const val MAX_GUIDE_PROGRAMS = 8
    }
}
