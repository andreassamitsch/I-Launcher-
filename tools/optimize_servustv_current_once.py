from pathlib import Path

path = Path('servustv-provider/src/main/java/com/andreassamitsch/servusprovider/data/ServusNewsRepository.kt')
text = path.read_text()


def patch(old: str, new: str) -> None:
    global text
    count = text.count(old)
    assert count == 1, f'Expected one matching source anchor, got {count}: {old[:110]!r}'
    text = text.replace(old, new, 1)


patch('import android.util.Log\n', 'import android.util.Log\nimport android.os.SystemClock\n')

patch('''            val candidates = discoverCurrentCandidates(market)
            val currentPlan = ServusCurrentRefreshPolicy.planCandidates(candidates, previousEpisodes)
            val details = fetchDetails(market, currentPlan.candidatesToLoad)
''', '''            val startedAt = SystemClock.elapsedRealtime()
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
''')

patch('''            newsStore.save(result)

            val liveChannels''', '''            newsStore.save(result)
            Log.i(
                TAG,
                "Aktuelles refresh: discovery=${discoveryMs}ms, remainingDetails=${detailMs}ms, " +
                    "firstCache=${SystemClock.elapsedRealtime() - startedAt}ms, " +
                    "candidates=${discovery.candidates.size}, prefetched=${discovery.earlyDetails.size}, " +
                    "late=${remainingCandidates.size}, episodes=${episodes.size}",
            )

            val liveChannels''')

patch('''    private suspend fun discoverCurrentCandidates(market: String): List<ServusCurrentCandidate> = coroutineScope {
        val responseGroups''', '''    private suspend fun discoverCurrentCandidates(
        market: String,
        previousEpisodes: List<ServusNewsEpisode>,
    ): CurrentDiscoveryResult = coroutineScope {
        val searchStartedAt = SystemClock.elapsedRealtime()
        val responseGroups''')

patch('''        val contentPages = directCards.filter''', '''        val searchMs = SystemClock.elapsedRealtime() - searchStartedAt
        // Overlap a small number of new direct video products with collection discovery. Avoid
        // prefetching page products: their collections are already requested by the discovery.
        val pageIds = directCards.asSequence().filter { it.type == "page" }.mapNotNull { it.id }.toSet()
        val earlyCandidates = ServusCurrentRefreshPolicy.planCandidates(
            directCandidates, previousEpisodes,
        ).candidatesToLoad.filterNot { it.id in pageIds }.take(MAX_DIRECT_PREFETCH_CANDIDATES)
        val earlyDetails = async {
            fetchDetails(market, earlyCandidates, parallelism = DIRECT_PREFETCH_PARALLELISM)
        }
        val contentPages = directCards.filter''')

patch('''        mergeCurrentCandidates(directCandidates + collectionCandidates).take(MAX_DETAIL_CANDIDATES)
    }
''', '''        val candidates = mergeCurrentCandidates(directCandidates + collectionCandidates).take(MAX_DETAIL_CANDIDATES)
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
''')

patch('''        candidates: List<ServusCurrentCandidate>,
    ): List<HydratedCurrentCandidate> = coroutineScope {
        val semaphore = Semaphore(DETAIL_PARALLELISM)
''', '''        candidates: List<ServusCurrentCandidate>,
        parallelism: Int = DETAIL_PARALLELISM,
    ): List<HydratedCurrentCandidate> = coroutineScope {
        val semaphore = Semaphore(parallelism)
''')

patch('''    private data class HydratedCurrentCandidate(val card: ServusCardDto, val contentKindHint: ServusContentKind?)
''', '''    private data class HydratedCurrentCandidate(val card: ServusCardDto, val contentKindHint: ServusContentKind?)
    private data class CurrentDiscoveryResult(
        val candidates: List<ServusCurrentCandidate>,
        val earlyDetails: List<HydratedCurrentCandidate>,
    )
''')

patch('''        const val DETAIL_PARALLELISM = 6
''', '''        const val DETAIL_PARALLELISM = 6
        const val MAX_DIRECT_PREFETCH_CANDIDATES = 8
        const val DIRECT_PREFETCH_PARALLELISM = 2
''')

path.write_text(text)
print('Patched bounded concurrent ServusTV prefetch and timing logs.')
