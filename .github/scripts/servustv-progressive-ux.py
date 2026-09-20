"""One-off strictly matched source patch, committed only after the Android regression build."""
from pathlib import Path

root = Path('servustv-provider/src/main/java/com/andreassamitsch/servusprovider')

def change(path, old, new):
    source = path.read_text()
    assert source.count(old) == 1, f'Expected exactly one replacement in {path}: {old[:85]!r} (got {source.count(old)})'
    path.write_text(source.replace(old, new, 1))

repo = root / 'data/ServusNewsRepository.kt'
change(repo,
       'import kotlinx.coroutines.sync.Semaphore\n',
       'import kotlinx.coroutines.sync.Semaphore\nimport java.util.concurrent.atomic.AtomicBoolean\n')
change(repo,
       '''        // Bound category pagination instead of issuing every category request at once.
        val categorySemaphore = Semaphore(CATALOG_CATEGORY_PARALLELISM)''',
       '''        // A cold cache can show the first useful category without waiting for every
        // category's remaining pages. The regular complete snapshot always replaces it.
        val firstCategoryPublished = AtomicBoolean(false)
        // Bound category pagination instead of issuing every category request at once.
        val categorySemaphore = Semaphore(CATALOG_CATEGORY_PARALLELISM)''')
change(repo,
       '''                        val (first, cards) = categorySemaphore.withPermit {
                            val first = api.collection(market, collectionId, 0)
                            first to fetchCollectionCards(market, collectionId, first, MAX_CATEGORY_PAGES)
                        }
                        val showCards = cards.filter(ServusCatalogPolicy::isShowCard)''',
       '''                        val (first, cards) = categorySemaphore.withPermit {
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
                        val showCards = cards.filter(ServusCatalogPolicy::isShowCard)''')

pager = root / 'data/ServusShowPager.kt'
change(pager,
       '    suspend fun refresh(cachedShow: ServusShow): PageResult {',
       '    suspend fun refresh(cachedShow: ServusShow, onPreview: suspend (PageResult) -> Unit = {}): PageResult {')
change(pager,
       '''        states[cachedShow.id] = state
        persistShow(state.show)
        return loadNextInternal(state)''',
       '''        states[cachedShow.id] = state
        // Usable first-page episode cards are visible before optional product details or
        // further pagination. Incomplete cards remain in sourceCards for normal hydration.
        val previewEpisodes = eligibleCandidates(state)
            .take(ServusShowPagingPolicy.PAGE_SIZE)
            .mapNotNull { candidate ->
                val card = candidate.card
                val existing = state.show.episodes.firstOrNull { it.id == card.id }
                if (card.duration?.let { it > 0L } != true ||
                    (card.longDescription.isNullOrBlank() && card.shortDescription.isNullOrBlank()) ||
                    (card.sunriseTimestamp.isNullOrBlank() && existing?.publishedAtMillis == null)
                ) return@mapNotNull null
                val parentId = candidate.contentShowId ?: state.show.id
                val parentTitle = candidate.contentShowTitle ?: state.show.title
                ServusCatalogPolicy.toShowEpisode(
                    candidate = candidate,
                    showId = parentId,
                    showTitle = parentTitle,
                    categoryId = state.show.categoryId,
                    categoryTitle = state.show.categoryTitle,
                    showLogoUri = if (parentId == state.show.id) state.show.logoUri
                        else ServusBranding.logoUriForShow(parentId, null),
                    nowMillis = System.currentTimeMillis(),
                )?.let(ServusBranding::canonicalizeEpisode)
            }
        if (previewEpisodes.isNotEmpty()) {
            val merged = ServusShowPagingPolicy.mergeEpisodes(state.show.episodes, previewEpisodes)
            state.show = state.show.copy(
                episodes = merged,
                collections = state.show.collections.map { collection ->
                    if (collection.role != ServusCollectionRole.CONTENT) collection else collection.copy(
                        episodes = merged.filter { it.sourceCollectionId == collection.id },
                    )
                },
            )
        }
        persistShow(state.show)
        onPreview(PageResult(state.show, hasMore = true))
        return loadNextInternal(state)''')

show = root / 'ui/ShowActivity.kt'
change(show,
       '''    private var currentShow: ServusShow? = null
    private var hasMoreEpisodes = false''',
       '''    private var currentShow: ServusShow? = null
    private var displayedEpisodes: List<ServusNewsEpisode> = emptyList()
    private var hasMoreEpisodes = false''')
change(show,
       '''            val result = runCatching { withContext(Dispatchers.IO) { pager.refresh(cachedShow) } }''',
       '''            val result = runCatching {
                withContext(Dispatchers.IO) {
                    pager.refresh(cachedShow) { preview ->
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                val focusId = currentFocus?.tag as? String
                                currentShow = preview.show
                                updateHeader(preview.show)
                                replaceEpisodeCards(preview.show.episodes, focusId)
                                updateLoadState()
                            }
                        }
                    }
                }
            }''')
change(show,
       '''    private fun replaceEpisodeCards(episodes: List<ServusNewsEpisode>, focusId: String?) {
        episodeCardsContainer.removeAllViews()''',
       '''    private fun replaceEpisodeCards(episodes: List<ServusNewsEpisode>, focusId: String?) {
        if (displayedEpisodes == episodes && episodeCardsContainer.childCount == episodes.size) return
        displayedEpisodes = episodes
        episodeCardsContainer.removeAllViews()''')

main = root / 'ui/MainActivity.kt'
change(main,
       '''    private var liveChannels: List<ServusLiveChannel> = emptyList()

    override fun onCreate''',
       '''    private var liveChannels: List<ServusLiveChannel> = emptyList()
    // Reuse an unchanged rail (and its artwork views + horizontal scroll state) when another
    // independent source refreshes. Cards remain identified by their stable content IDs.
    private var railViews = mutableMapOf<String, Pair<Any, HorizontalScrollView>>()
    private var previousRailViews: Map<String, Pair<Any, HorizontalScrollView>> = emptyMap()

    override fun onCreate''')
change(main,
       '''        val focusedContentId = contentContainer.findFocus()?.tag as? String
        contentContainer.removeAllViews()''',
       '''        val focusedContentId = contentContainer.findFocus()?.tag as? String
        previousRailViews = railViews
        railViews = mutableMapOf()
        contentContainer.removeAllViews()''')
change(main,
       '            addRail(episodes.take(MAX_CURRENT_UI_ITEMS).map(::buildEpisodeCard))',
       '''            val visibleEpisodes = episodes.take(MAX_CURRENT_UI_ITEMS)
            addRail("current", visibleEpisodes) { visibleEpisodes.map(::buildEpisodeCard) }''')
change(main,
       '            addRail(liveChannels.map(::buildLiveCard))',
       '            addRail("live", liveChannels) { liveChannels.map(::buildLiveCard) }')
change(main,
       '                addRail(category.shows.map(::buildShowCard))',
       '''                val appearance = category.shows.map { show ->
                    Triple(show, currentSelectionStore.isSelected(show, categories), repository.isShowChannelSelected(show.id))
                }
                addRail("category:${category.id}", appearance) { category.shows.map(::buildShowCard) }''')
change(main,
       '''        if (focusedContentId != null &&
            contentContainer.findViewWithTag<View>(focusedContentId)?.requestFocus() != true
        ) {
            refreshButton.requestFocus()
        }
    }

    private fun addSectionTitle''',
       '''        if (focusedContentId != null &&
            contentContainer.findViewWithTag<View>(focusedContentId)?.requestFocus() != true
        ) {
            refreshButton.requestFocus()
        }
        previousRailViews = emptyMap()
    }

    private fun addSectionTitle''')
start = '    private fun addRail(cards: List<View>) {'
end = '\n    private fun buildEpisodeCard('
src = main.read_text()
a = src.index(start)
b = src.index(end, a)
old = src[a:b]
new = '''    private fun addRail(key: String, signature: Any, cards: () -> List<View>) {
        val cached = previousRailViews[key]
        val scroller = if (cached?.first == signature) {
            cached.second
        } else {
            val oldScrollX = cached?.second?.scrollX ?: 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(2), dp(2), dp(2), dp(8))
            }
            cards().forEach { card ->
                row.addView(
                    card,
                    LinearLayout.LayoutParams(
                        dp(if (isTvDevice) CARD_WIDTH_TV_DP else CARD_WIDTH_PHONE_DP),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(if (isTvDevice) 14 else 10) },
                )
            }
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
                if (oldScrollX > 0) post { scrollTo(oldScrollX, 0) }
            }
        }
        railViews[key] = signature to scroller
        contentContainer.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
'''
main.write_text(src[:a] + new + src[b:])
print('Progressive first category and episode previews; reused unchanged rails; preserved D-Pad content ID and horizontal scroll offset.')
