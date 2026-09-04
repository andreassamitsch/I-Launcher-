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
    private val showChannelSelectionStore = ServusShowChannelSelectionStore(appContext)
    private val observedAvailabilityStore = ServusObservedAvailabilityStore(appContext)
    private val channelPublisher = ServusChannelPublisher(appContext)
    private val showEpisodeDetailSemaphore = Semaphore(SHOW_EPISODE_DETAIL_PARALLELISM)

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

    fun isShowChannelSelected(showId: String): Boolean = showChannelSelectionStore.isSelected(showId)

    fun selectedShowChannelIds(categories: List<ServusCategory> = hubStore.loadCategories()): Set<String> =
        showChannelSelectionStore.effectiveSelectedShowIds(categories)

    fun setShowChannelSelected(showId: String, selected: Boolean) {
        if (selected) {
            hubStore.findShow(showId)?.let { show -> observedAvailabilityStore.baseline(show.episodes) }
        }
        showChannelSelectionStore.setSelected(showId, selected)
        if (!selected) {
            runCatching { channelPublisher.removeShowChannel(showId) }
                .onFailure { throwable ->
                    Log.w(TAG, "Immediate show-channel removal skipped (${throwable.javaClass.simpleName})")
                }
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
     * metadata-only and deliberately slower: initially, when stale, or on an explicit forced refresh.
     * Episode collections are not traversed for every catalogue show. Periodic episode traffic is
     * limited to explicitly added Aktuelles shows and shows opted into an Android-TV channel. Any
     * other show loads its episodes only when the user opens that show in the app.
     */
    suspend fun refresh(forceCatalog: Boolean = false): ServusRefreshResult {
        return try {
            val previousEpisodes = newsStore.loadEpisodes()
            val session = sessionStore.get()
            val market = session.countryCode
            val refreshNow = System.currentTimeMillis()
            val detectNewAvailability = observedAvailabilityStore.isInitialized()

            val candidates = discoverCurrentCandidates(market)
            val currentPlan = ServusCurrentRefreshPolicy.planCandidates(candidates, previousEpisodes)
            val details = fetchDetails(market, currentPlan.candidatesToLoad)
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
                    val outcome = refreshShowCatalog(market, refreshNow, cachedCategories)
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

            val periodicShowIds = ServusShowRefreshPolicy.periodicShowIds(
                categories = categories,
                currentSelectionConfigured = currentSelectionStore.isConfigured(),
                currentSelectedIds = currentSelectionStore.effectiveSelectedShowIds(categories),
                tvChannelSelectedIds = showChannelSelectionStore.effectiveSelectedShowIds(categories),
            )
            var periodicShowsRefreshed = false
            if (categories.isNotEmpty() && periodicShowIds.isNotEmpty()) {
                val targeted = refreshSubscribedShows(market, categories, refreshNow, periodicShowIds)
                categories = targeted.categories
                periodicShowsRefreshed = targeted.changed
            }

            val beforeAvailabilityAnnotation = categories
            categories = annotateTrackedShowAvailability(
                categories = categories,
                trackedShowIds = periodicShowIds,
                observedAtMillis = refreshNow,
                detectNewItems = detectNewAvailability,
            )
            val trackedAvailabilityChanged = categories != beforeAvailabilityAnnotation

            if (catalogRefreshSucceeded) {
                hubStore.saveCatalog(categories, refreshNow)
            } else if (periodicShowsRefreshed || trackedAvailabilityChanged) {
                hubStore.saveCatalogContent(categories)
            }

            val trackedCatalogueEpisodes = categories
                .flatMap { it.shows }
                .filter { it.id in periodicShowIds }
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
                    val showChannelSelectionChanged = showChannelSelectionStore.needsTvProviderSync()
                    if ((catalogRefreshSucceeded || periodicShowsRefreshed || showChannelSelectionChanged) &&
                        categories.isNotEmpty()
                    ) {
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

    /**
     * Candidate discovery keeps the source collection long enough to classify cards whose own
     * product metadata is intentionally sparse. This is required for today's 90-second clips: they
     * have topical titles and no show_name, while the enclosing collection is explicitly named
     * "Servus Nachrichten in 90 Sekunden".
     */
    private suspend fun discoverCurrentCandidates(market: String): List<ServusCurrentCandidate> = coroutineScope {
        val responseGroups = SEARCH_QUERIES.map { query ->
            async { fetchCurrentSearchPages(market, query) }
        }.awaitAll()

        val directCards = responseGroups.flatten().flatMap { it.cards }
        val directCandidates = responseGroups.flatMap { responses ->
            responses
                .flatMap { it.cards }
                .filter(ServusNewsPolicy::couldBelongToSupportedContent)
                .mapNotNull { card ->
                    card.id?.let { id -> ServusCurrentCandidate(id, ServusNewsPolicy.contentKind(card)) }
                }
                .distinctBy { it.id }
                .take(MAX_DIRECT_IDS_PER_QUERY)
        }

        val contentPages = directCards.filter { card ->
            card.type == "page" && ServusNewsPolicy.couldBelongToSupportedContent(card)
        }.distinctBy { it.id }

        val collectionSources = contentPages
            .take(MAX_CONTENT_PAGES)
            .map { page ->
                async {
                    val pageId = page.id ?: return@async emptyList()
                    val product = runCatching { api.product(market, pageId) }.getOrNull()
                        ?: return@async emptyList()
                    val ownerTitle = product.title?.takeIf { it.isNotBlank() }
                        ?: page.title?.takeIf { it.isNotBlank() }
                    product.collections
                        .filterNot { it.listType == "reference" }
                        .mapNotNull { ref ->
                            ref.id?.let { collectionId ->
                                CurrentCollectionSource(
                                    collectionId = collectionId,
                                    referenceLabel = ref.label,
                                    ownerShowId = pageId,
                                    ownerShowTitle = ownerTitle,
                                )
                            }
                        }
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.collectionId }
            .take(MAX_CURRENT_COLLECTIONS)

        val collectionCandidates = collectionSources.map { source ->
            async {
                val response = runCatching { api.collection(market, source.collectionId, 0) }
                    .getOrNull()
                    ?: return@async emptyList()
                val label = response.label?.takeIf { it.isNotBlank() } ?: source.referenceLabel
                val collectionHint = ServusCatalogPolicy.contentKindForCollection(
                    ownerShowId = source.ownerShowId,
                    ownerShowTitle = source.ownerShowTitle,
                    collectionLabel = label,
                )
                response.cards.mapNotNull { card ->
                    val id = card.id ?: return@mapNotNull null
                    val cardHint = ServusNewsPolicy.contentKind(card)
                    val hint = collectionHint ?: cardHint
                    if (hint != null || ServusNewsPolicy.couldBelongToSupportedContent(card)) {
                        ServusCurrentCandidate(id, hint)
                    } else {
                        null
                    }
                }
            }
        }.awaitAll().flatten()

        mergeCurrentCandidates(directCandidates + collectionCandidates)
            .take(MAX_DETAIL_CANDIDATES)
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

    /** Follow only pagination links the ServusTV API actually advertises. */
    private suspend fun fetchCurrentSearchPages(
        market: String,
        query: String,
    ): List<SearchResponseDto> {
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
    ): List<HydratedCurrentCandidate> = coroutineScope {
        val semaphore = Semaphore(DETAIL_PARALLELISM)
        candidates.map { candidate ->
            async {
                semaphore.withPermit {
                    runCatching { api.product(market, candidate.id) }
                        .getOrNull()
                        ?.let { card -> HydratedCurrentCandidate(card, candidate.contentKindHint) }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun refreshShowCatalog(
        market: String,
        nowMillis: Long,
        cachedCategories: List<ServusCategory>,
    ): CatalogRefreshOutcome = coroutineScope {
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

        val cachedShowsById = cachedCategories
            .flatMap { it.shows }
            .distinctBy { it.id }
            .associateBy { it.id }
        val cores = uniqueShowCards.values.mapNotNull { card ->
            val cachedShow = card.id?.let(cachedShowsById::get)
            showMetadataFromCard(card, cachedShow)
        }.associateBy { it.id }

        val categories = seeds.map { seed ->
            val shows = seed.cards.mapNotNull { card ->
                val id = card.id ?: return@mapNotNull null
                val core = cores[id] ?: return@mapNotNull null
                val episodes = core.episodes.map { episode ->
                    ServusBranding.canonicalizeEpisode(
                        episode.copy(
                            categoryId = seed.id,
                            categoryTitle = seed.title,
                        ),
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
                    logoUri = ServusBranding.logoUriForShow(core.id, core.logoUri),
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

    private suspend fun refreshSubscribedShows(
        market: String,
        categories: List<ServusCategory>,
        nowMillis: Long,
        showIds: Set<String>,
    ): SubscribedShowRefreshOutcome = coroutineScope {
        val selectedShows = categories
            .flatMap { it.shows }
            .distinctBy { it.id }
            .filter { show -> show.id in showIds }
        if (selectedShows.isEmpty()) {
            return@coroutineScope SubscribedShowRefreshOutcome(categories, changed = false)
        }

        val semaphore = Semaphore(SUBSCRIBED_SHOW_PARALLELISM)
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
                            maxCollectionPages = MAX_SUBSCRIBED_SHOW_COLLECTION_PAGES,
                            cachedEpisodes = show.episodes,
                        )
                    }.getOrNull()?.let { show.id to it }
                }
            }
        }.awaitAll().filterNotNull().toMap()

        if (refreshedById.isEmpty()) {
            return@coroutineScope SubscribedShowRefreshOutcome(categories, changed = false)
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
                        logoUri = ServusBranding.logoUriForShow(show.id, core.logoUri ?: show.logoUri),
                        episodes = ServusCatalogPolicy.selectChannelEpisodes(core.episodes + show.episodes)
                            .map(ServusBranding::canonicalizeEpisode),
                    )
                },
            )
        }
        SubscribedShowRefreshOutcome(updated, changed = updated != categories)
    }

    private fun annotateTrackedShowAvailability(
        categories: List<ServusCategory>,
        trackedShowIds: Set<String>,
        observedAtMillis: Long,
        detectNewItems: Boolean,
    ): List<ServusCategory> {
        if (categories.isEmpty() || trackedShowIds.isEmpty()) return categories

        return categories.map { category ->
            category.copy(
                shows = category.shows.map { show ->
                    if (show.id !in trackedShowIds || show.episodes.isEmpty()) return@map show
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

    suspend fun refreshShow(showId: String): ServusShow? {
        val categories = hubStore.loadCategories()
        val cachedShow = categories.asSequence()
            .flatMap { it.shows.asSequence() }
            .firstOrNull { it.id == showId }
            ?: return null
        val session = sessionStore.get()
        val nowMillis = System.currentTimeMillis()
        val core = loadShowCore(
            market = session.countryCode,
            card = ServusCardDto(
                id = cachedShow.id,
                type = "page",
                title = cachedShow.title,
                longDescription = cachedShow.description,
            ),
            nowMillis = nowMillis,
            maxCollectionPages = MAX_SHOW_COLLECTION_PAGES,
            cachedEpisodes = cachedShow.episodes,
        ) ?: return cachedShow
        val annotatedEpisodes = observedAvailabilityStore.annotateNewlyObserved(
            episodes = core.episodes.map(ServusBranding::canonicalizeEpisode),
            observedAtMillis = nowMillis,
            detectNewItems = observedAvailabilityStore.isInitialized(),
        )
        val updatedCategories = categories.map { category ->
            category.copy(
                shows = category.shows.map { show ->
                    if (show.id != showId) return@map show
                    show.copy(
                        title = core.title,
                        description = core.description ?: show.description,
                        artworkUri = core.artworkUri ?: show.artworkUri,
                        squareArtworkUri = core.squareArtworkUri ?: show.squareArtworkUri,
                        logoUri = ServusBranding.logoUriForShow(show.id, core.logoUri ?: show.logoUri),
                        episodes = annotatedEpisodes.map { episode ->
                            ServusBranding.canonicalizeEpisode(
                                episode.copy(
                                    categoryId = category.id,
                                    categoryTitle = category.title,
                                ),
                            )
                        },
                    )
                },
            )
        }
        hubStore.saveCatalogContent(updatedCategories)
        observedAvailabilityStore.finishSuccessfulRefresh(annotatedEpisodes)

        val refreshedShow = updatedCategories.asSequence()
            .flatMap { it.shows.asSequence() }
            .firstOrNull { it.id == showId }
        if (channelPublisher.isSupported() && refreshedShow != null) {
            runCatching {
                if (currentSelectionStore.isSelected(refreshedShow, updatedCategories)) {
                    channelPublisher.publish(newsStore.loadEpisodes())
                }
                if (showChannelSelectionStore.isSelected(showId)) {
                    channelPublisher.publishShows(updatedCategories)
                }
            }.onFailure { throwable ->
                Log.w(TAG, "TvProvider sync skipped after show refresh (${throwable.javaClass.simpleName})")
            }
        }
        return refreshedShow
    }

    private fun showMetadataFromCard(card: ServusCardDto, cachedShow: ServusShow?): ShowCore? {
        val id = card.id ?: return null
        val title = card.title?.takeIf { it.isNotBlank() }
            ?: cachedShow?.title?.takeIf { it.isNotBlank() }
            ?: return null
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
        )
    }

    private suspend fun loadShowCore(
        market: String,
        card: ServusCardDto,
        nowMillis: Long,
        maxCollectionPages: Int = MAX_SHOW_COLLECTION_PAGES,
        cachedEpisodes: List<ServusNewsEpisode> = emptyList(),
    ): ShowCore? {
        val id = card.id ?: return null
        val detail = runCatching { api.product(market, id) }.getOrNull()
        val title = detail?.title?.takeIf { !it.isNullOrBlank() }
            ?: card.title?.takeIf { it.isNotBlank() }
            ?: return null
        val resources = (detail?.mediaResources.orEmpty() + card.mediaResources).distinct()
        val logoUri = ServusBranding.logoUriForShow(id, ServusCatalogPolicy.titleTreatment(id, resources))
        val collectionRefs = detail?.collections.orEmpty()
            .filter { it.listType != "reference" && !it.id.isNullOrBlank() }
            .take(MAX_SHOW_COLLECTIONS)

        val sourcedCards = coroutineScope {
            collectionRefs.map { ref ->
                async {
                    val collectionId = requireNotNull(ref.id)
                    val first = runCatching { api.collection(market, collectionId, 0) }.getOrNull()
                        ?: return@async emptyList()
                    val label = first.label?.takeIf { it.isNotBlank() } ?: ref.label
                    val kindHint = ServusCatalogPolicy.contentKindForCollection(
                        ownerShowId = id,
                        ownerShowTitle = title,
                        collectionLabel = label,
                    )
                    fetchCollectionCards(market, collectionId, first, maxCollectionPages).map { episodeCard ->
                        ServusSourcedCard(
                            card = episodeCard,
                            sourceCollectionId = collectionId,
                            sourceCollectionLabel = label,
                            contentKindHint = kindHint ?: ServusNewsPolicy.contentKind(episodeCard),
                        )
                    }
                }
            }.awaitAll().flatten()
        }

        val candidateCards = ServusCatalogPolicy.selectSourcedEpisodeCardsForHydration(
            candidates = sourcedCards,
            showId = id,
            showTitle = title,
            limit = MAX_SHOW_EPISODE_DETAIL_CANDIDATES,
        )
        val hydratedCards = hydrateShowEpisodeCards(
            market = market,
            candidates = candidateCards,
            cachedEpisodes = cachedEpisodes,
        )
        val cachedById = cachedEpisodes.associateBy { it.id }
        val mapped = hydratedCards.mapNotNull { hydrated ->
            val episodeCard = hydrated.candidate.card
            val cachedEpisode = episodeCard.id?.let { episodeId -> cachedById[episodeId] }
            val fresh = ServusCatalogPolicy.toShowEpisode(
                candidate = hydrated.candidate,
                showId = id,
                showTitle = title,
                categoryId = "",
                categoryTitle = "",
                showLogoUri = logoUri,
                nowMillis = nowMillis,
            )
            when {
                fresh != null -> {
                    val cachedPublishedAtMillis = cachedEpisode?.publishedAtMillis
                    if (fresh.publishedAtMillis == null && cachedPublishedAtMillis != null) {
                        fresh.copy(publishedAtMillis = cachedPublishedAtMillis)
                    } else {
                        fresh
                    }
                }

                !hydrated.productDetailLoaded && episodeCard.playable != false && cachedEpisode != null -> {
                    val hint = hydrated.candidate.contentKindHint ?: cachedEpisode.contentKindHint
                    val fallback = if (hint == null) {
                        cachedEpisode.copy(
                            showName = cachedEpisode.showName?.takeIf { it.isNotBlank() } ?: title,
                            showId = cachedEpisode.showId ?: id,
                            logoUri = cachedEpisode.logoUri ?: logoUri,
                            categoryId = "",
                            categoryTitle = "",
                        )
                    } else {
                        cachedEpisode.copy(
                            contentKindHint = hint,
                            categoryId = "",
                            categoryTitle = "",
                        )
                    }
                    ServusBranding.canonicalizeEpisode(fallback)
                }

                else -> null
            }
        }
        val episodes = if (sourcedCards.isEmpty() && cachedEpisodes.isNotEmpty()) {
            ServusCatalogPolicy.selectChannelEpisodes(cachedEpisodes.map(ServusBranding::canonicalizeEpisode))
        } else {
            ServusCatalogPolicy.selectChannelEpisodes(mapped.map(ServusBranding::canonicalizeEpisode))
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
            episodes = episodes,
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
                val id = card.id ?: return@async HydratedShowEpisodeCard(candidate, productDetailLoaded = false)
                val cachedPublishedAtMillis = cachedById[id]?.publishedAtMillis
                val hasReusableSourceTime = !card.sunriseTimestamp.isNullOrBlank() || cachedPublishedAtMillis != null
                if (hasReusableSourceTime && canMapShowEpisodeWithoutProduct(card)) {
                    HydratedShowEpisodeCard(candidate, productDetailLoaded = false)
                } else {
                    showEpisodeDetailSemaphore.withPermit {
                        val detail = runCatching { api.product(market, id) }.getOrNull()
                        if (detail == null) {
                            HydratedShowEpisodeCard(candidate, productDetailLoaded = false)
                        } else {
                            HydratedShowEpisodeCard(
                                candidate = candidate.copy(
                                    card = ServusCatalogPolicy.mergeEpisodeProduct(card, detail),
                                ),
                                productDetailLoaded = true,
                            )
                        }
                    }
                }
            }
        }.awaitAll()
    }

    private fun canMapShowEpisodeWithoutProduct(card: ServusCardDto): Boolean =
        card.duration?.let { it > 0L } == true &&
            card.playable != false &&
            (card.type == "video" || card.contentType == "film")

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

    private data class HydratedCurrentCandidate(
        val card: ServusCardDto,
        val contentKindHint: ServusContentKind?,
    )

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

    private data class SubscribedShowRefreshOutcome(
        val categories: List<ServusCategory>,
        val changed: Boolean,
    )

    private data class HydratedShowEpisodeCard(
        val candidate: ServusSourcedCard,
        val productDetailLoaded: Boolean,
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
        const val MAX_CURRENT_SEARCH_PAGES = 3
        const val MAX_DIRECT_IDS_PER_QUERY = 16
        const val DETAIL_PARALLELISM = 6
        const val MAX_CONTENT_PAGES = 8
        const val MAX_CURRENT_COLLECTIONS = 12
        const val MAX_DETAIL_CANDIDATES = 72
        const val MAX_CURRENT_EPISODES = 40

        const val SHOW_EPISODE_DETAIL_PARALLELISM = 6
        const val MAX_SHOW_EPISODE_DETAIL_CANDIDATES = 20
        const val MAX_CATEGORY_PAGES = 20
        const val MAX_SHOW_COLLECTIONS = 5
        const val MAX_SHOW_COLLECTION_PAGES = 3
        const val SUBSCRIBED_SHOW_PARALLELISM = 2
        const val MAX_SUBSCRIBED_SHOW_COLLECTION_PAGES = 1
        const val MAX_LIVE_PAGES = 3
        const val LIVE_GUIDE_PARALLELISM = 4
        const val MAX_GUIDE_PROGRAMS = 8
    }
}
