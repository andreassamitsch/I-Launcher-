from pathlib import Path

path = Path('servustv-provider/src/main/java/com/andreassamitsch/servusprovider/data/ServusNewsRepository.kt')
source = path.read_text()
changes = [
    (
        '''                    hubStore.saveCatalogDiagnostic(outcome.diagnostic)
                    catalogRefreshSucceeded = true
                    outcome.categories''',
        '''                    hubStore.saveCatalogDiagnostic(outcome.diagnostic)
                    // Show the newly loaded catalogue before hydrating selected shows and TV channels.
                    hubStore.saveCatalog(outcome.categories, refreshNow)
                    Log.i(TAG, "Katalog cache: categories=${outcome.categories.size}, " +
                        "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms")
                    catalogRefreshSucceeded = true
                    outcome.categories''',
    ),
    (
        '''            if (catalogRefreshSucceeded) {
                hubStore.saveCatalog(categories, refreshNow)
            } else if (periodicShowsRefreshed || trackedAvailabilityChanged) {
                hubStore.saveCatalogContent(categories)
            }''',
        '''            // The metadata snapshot is already visible. Write again only for actual
            // subscribed-show or availability changes, avoiding a redundant full UI redraw.
            if (periodicShowsRefreshed || trackedAvailabilityChanged) {
                hubStore.saveCatalogContent(categories)
            }''',
    ),
    (
        '''        val categoryLoads = try {
            categoryRefs.mapIndexed { order, ref ->''',
        '''        // Bound category pagination instead of issuing every category request at once.
        val categorySemaphore = Semaphore(CATALOG_CATEGORY_PARALLELISM)
        val categoryLoads = try {
            categoryRefs.mapIndexed { order, ref ->''',
    ),
    (
        '''                        val first = api.collection(market, collectionId, 0)
                        val cards = fetchCollectionCards(market, collectionId, first, MAX_CATEGORY_PAGES)
                        val showCards = cards.filter(ServusCatalogPolicy::isShowCard)''',
        '''                        val (first, cards) = categorySemaphore.withPermit {
                            val first = api.collection(market, collectionId, 0)
                            first to fetchCollectionCards(market, collectionId, first, MAX_CATEGORY_PAGES)
                        }
                        val showCards = cards.filter(ServusCatalogPolicy::isShowCard)''',
    ),
    (
        '''        const val MAX_CATEGORY_PAGES = 20
        const val MAX_SHOW_COLLECTIONS = 8''',
        '''        const val MAX_CATEGORY_PAGES = 20
        const val CATALOG_CATEGORY_PARALLELISM = 4
        const val MAX_SHOW_COLLECTIONS = 8''',
    ),
]

for before, after in changes:
    count = source.count(before)
    assert count == 1, f'Expected one matching source span but found {count}: {before[:60]!r}'
    source = source.replace(before, after, 1)

path.write_text(source)
print('Catalog snapshot moved ahead of subscribed-show fetch; category concurrency limited to four.')
