from pathlib import Path

root = Path('servustv-provider')
repository = root / 'src/main/java/com/andreassamitsch/servusprovider/data/ServusNewsRepository.kt'
source = repository.read_text()

def replace_once(before: str, after: str) -> None:
    global source
    assert source.count(before) == 1, f'Expected one source anchor; got {source.count(before)}: {before[:100]!r}'
    source = source.replace(before, after, 1)

replace_once('import kotlinx.coroutines.async\n', 'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.async\n')
replace_once('''    private suspend fun discoverCurrentCandidates(
        market: String,
        previousEpisodes: List<ServusNewsEpisode>,
    ): CurrentDiscoveryResult = coroutineScope {
        val searchStartedAt''', '''    /**
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
        val searchStartedAt''')
replace_once('''        const val DIRECT_PREFETCH_PARALLELISM = 2
''', '''        const val DIRECT_PREFETCH_PARALLELISM = 2
        const val WEGSCHEIDER_SHOW_ID = "AA-1Q66UK71N1W11"
        const val DIRECT_COLLECTION_PARALLELISM = 4
        const val MAX_DIRECT_COLLECTIONS_PER_SHOW = 8
''')
repository.write_text(source)

policy = root / 'src/main/java/com/andreassamitsch/servusprovider/data/ServusDirectCurrentPolicy.kt'
assert not policy.exists(), policy
policy.write_text('''package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto

/** Editorial show collections: never mistake recommendations or individual news clips for full editions. */
internal object ServusDirectCurrentPolicy {
    private const val WEGSCHEIDER_SHOW_ID = "AA-1Q66UK71N1W11"

    fun candidatesForCollection(
        rootShowId: String,
        label: String?,
        cards: List<ServusCardDto>,
    ): List<ServusCurrentCandidate> {
        val kind = when (rootShowId) {
            ServusBranding.NEWS_SHOW_ID -> ServusCatalogPolicy.contentKindForCollection(
                rootShowId, ServusBranding.NEWS_SHOW_NAME, label,
            )
            WEGSCHEIDER_SHOW_ID -> if (label?.trim().equals("Aktuelle Sendungen", ignoreCase = true)) {
                ServusContentKind.WEGSCHEIDER
            } else null
            else -> null
        } ?: return emptyList()

        return cards.mapNotNull { card ->
            val id = card.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (card.title.isNullOrBlank() || card.playable == false ||
                !(card.type == "video" || card.contentType in listOf("episode", "clip", "film"))
            ) return@mapNotNull null
            ServusCurrentCandidate(id, kind)
        }.distinctBy { it.id }
    }
}
''')

tests = root / 'src/test/java/com/andreassamitsch/servusprovider/data/ServusDirectCurrentPolicyTest.kt'
assert not tests.exists(), tests
tests.write_text('''package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusDirectCurrentPolicyTest {
    private val playable = ServusCardDto(id = "episode-1", title = "Folge", type = "video", playable = true)

    @Test fun newsPageUsesEditorialFormatLabels() {
        val showId = ServusBranding.NEWS_SHOW_ID
        assertEquals(ServusContentKind.NEWS_90_SECONDS,
            ServusDirectCurrentPolicy.candidatesForCollection(showId, "Servus Nachrichten in 90 Sekunden", listOf(playable)).single().contentKindHint)
        assertEquals(ServusContentKind.FULL_NEWS,
            ServusDirectCurrentPolicy.candidatesForCollection(showId, "Servus Nachrichten 19:20", listOf(playable)).single().contentKindHint)
        assertTrue(ServusDirectCurrentPolicy.candidatesForCollection(showId, "Servus Nachrichten: Einzelbeiträge", listOf(playable)).isEmpty())
        assertTrue(ServusDirectCurrentPolicy.candidatesForCollection(showId, "Das könnte Ihnen auch gefallen", listOf(playable)).isEmpty())
    }

    @Test fun separateWegscheiderFeedIgnoresRecommendations() {
        val showId = "AA-1Q66UK71N1W11"
        assertEquals(ServusContentKind.WEGSCHEIDER,
            ServusDirectCurrentPolicy.candidatesForCollection(showId, "Aktuelle Sendungen", listOf(playable)).single().contentKindHint)
        assertTrue(ServusDirectCurrentPolicy.candidatesForCollection(showId, "Das könnte Ihnen auch gefallen", listOf(playable)).isEmpty())
    }

    @Test fun excludesUnplayableInvalidAndDuplicateCards() {
        val cards = listOf(
            playable, playable,
            playable.copy(id = "hidden", playable = false),
            playable.copy(id = null),
            playable.copy(id = "navigation", type = "page", contentType = null),
        )
        assertEquals(listOf("episode-1"),
            ServusDirectCurrentPolicy.candidatesForCollection(ServusBranding.NEWS_SHOW_ID,
                "Servus Nachrichten 19:20", cards).map { it.id })
    }
}
''')

docs = root / 'REFRESH_PERFORMANCE.md'
docs.write_text(docs.read_text() + '''\n## Verifizierte direkte Quellen (20.09.2026)\n\nDer öffentliche `/de/sendungen`-Einstieg entspricht dem API-Produkt `sendungen`; dessen Katalog wird separat alle sechs Stunden geladen. Für den häufigen Aktuelles-Refresh werden die zwei verifizierten Show-IDs direkt abgefragt: `AA-1Y5RJCD1H2111` (Servus Nachrichten) und `AA-1Q66UK71N1W11` (Der Wegscheider). Deren API-Produktantworten liefern die Collection-IDs dynamisch. Im Nachrichten-Produkt sind die redaktionellen Collections „Servus Nachrichten in 90 Sekunden“ und „Servus Nachrichten 19:20“ die Quellen für die schnelle Aktuelles-Reihe; „Einzelbeiträge“ bleibt Bestandteil der Sendungsdetailansicht und wird nicht fälschlich als vollständige Nachrichtensendung einsortiert. Für den Wegscheider wird nur „Aktuelle Sendungen“ verwendet. „Das könnte Ihnen auch gefallen“ und „Mehr zu“ sind keine eigenen Sendungsfolgen.\n\nSind eine der erforderlichen Collection-Gruppen oder eine Show-API nicht erreichbar, bleibt die ursprüngliche vollständige Such-/Collection-Discovery als Fallback erhalten. Logcat `Aktuelles source=direct` bzw. `Aktuelles source=search-fallback` hilft beim Vergleich. Die bisherige begrenzte Vorab-Ladung greift weiterhin im Fallback. Die 20.09.2026 live überprüften Collection-IDs sind bewusst nicht fest verdrahtet, sondern werden aus dem Show-Produkt gelesen.\n''')
print('Installed verified direct current feed, fallback, regression tests, and documentation.')
