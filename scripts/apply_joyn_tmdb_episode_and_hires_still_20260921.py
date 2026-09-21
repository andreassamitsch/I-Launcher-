from pathlib import Path


def patch(path, old, new):
    file = Path(path)
    content = file.read_text()
    matches = content.count(old)
    if matches != 1:
        raise RuntimeError(f'{path}: expected 1 anchor; found {matches}: {old[:110]}')
    file.write_text(content.replace(old, new, 1))

app = 'app/src/main/java/com/andreassamitsch/ilauncher/'
joyn = 'joyntv/src/main/java/com/andreassamitsch/joyntv/'

# TMDB must prove Austrian series identity before returning an episode overview. Never overlay
# the German logo, title or poster on Joyn AT provider metadata.
dtos = app + 'data/tmdb/TmdbDtos.kt'
patch(dtos,
      '    @SerializedName("original_name") val originalName: String? = null,\n    val overview: String? = null,',
      '    @SerializedName("original_name") val originalName: String? = null,\n    @SerializedName("origin_country") val originCountry: List<String> = emptyList(),\n    val overview: String? = null,')
patch(dtos,
      '    @SerializedName("original_name") val originalName: String? = null,\n    val overview: String? = null,',
      '    @SerializedName("original_name") val originalName: String? = null,\n    @SerializedName("origin_country") val originCountry: List<String> = emptyList(),\n    val overview: String? = null,')
repo = app + 'data/tv/WatchNextEnrichmentRepository.kt'
patch(repo,
      'import com.andreassamitsch.ilauncher.data.tmdb.TmdbRepository\n',
      'import com.andreassamitsch.ilauncher.data.tmdb.TmdbRepository\nimport com.andreassamitsch.ilauncher.data.tmdb.JoynVerifiedEpisodeText\n')
patch(repo,
      '        if (media.source.packageName == JOYN_TV_PACKAGE) return media\n',
      '''        if (media.source.packageName == JOYN_TV_PACKAGE) {
            // Retain Joyn's authoritative Austrian series title, branding and episode imagery.
            // Only enrich the synopsis when the unique Austrian TMDB series and S/E are verified.
            val episodeText = JoynVerifiedEpisodeText.overview(media)
            return if (episodeText != null) media.copy(overview = episodeText) else media
        }
''')

api = joyn + 'JoynApiClient.kt'
patch(api,
      '''        val episodeThumbnail = if (type == JoynMediaType.EPISODE) {
            optJSONObject("thumbnailImage")?.urlValue()
        } else null''',
      '''        val episodeThumbnail = if (type == JoynMediaType.EPISODE) {
            optJSONArray("images").bestEpisodeStillUrl()
                ?: optJSONObject("thumbnailImage")?.urlValue()
        } else null''')
patch(api,
      '''    private fun JSONArray?.firstImageUrl(): String? {''',
      '''    /** Prefer the largest LIVE_STILL rendition explicitly returned for the same episode. */
    private fun JSONArray?.bestEpisodeStillUrl(): String? {
        val source = this ?: return null
        val size = Regex("(\\\\d{2,5})x(\\\\d{2,5})")
        return (0 until source.length())
            .mapNotNull { source.optJSONObject(it) }
            .filter { it.optString("type").uppercase() in setOf("LIVE_STILL", "EPISODE_STILL", "THUMBNAIL") }
            .mapNotNull { it.optString("url").takeIf(String::isNotBlank) }
            .maxByOrNull { url ->
                size.find(url.substringAfterLast("/profile:", ""))?.let { match ->
                    (match.groupValues[1].toLongOrNull() ?: 0L) *
                        (match.groupValues[2].toLongOrNull() ?: 0L)
                } ?: 0L
            }
    }

    private fun JSONArray?.firstImageUrl(): String? {''')

publisher = joyn + 'JoynContinueWatching.kt'
patch(publisher,
      'import android.os.Build\n',
      'import android.os.Build\nimport java.util.concurrent.ConcurrentHashMap\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.SupervisorJob\nimport kotlinx.coroutines.launch\n')
patch(publisher,
      '''    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun publish(entry: JoynContinueWatchingEntry) {''',
      '''    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val stills = JoynHighResEpisodeArtwork(appContext)
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probingStills = ConcurrentHashMap.newKeySet<String>()

    /** No network or bitmap work runs on the UI thread. Re-publish only existing rows. */
    private fun upgradeStill(media: JoynMediaItem, onUpgrade: () -> Unit) {
        if (media.type != JoynMediaType.EPISODE) return
        val url = media.imageUrl ?: return
        if (!stills.shouldProbe(url) || !probingStills.add(url)) return
        artworkScope.launch {
            try {
                if (stills.validatedOriginal(url) != null) onUpgrade()
            } finally {
                probingStills.remove(url)
            }
        }
    }

    fun publish(entry: JoynContinueWatchingEntry) {''')
patch(publisher,
      '''        if (isSuppressed(entry.assetId)) return

        val program = buildProgram(entry)''',
      '''        if (isSuppressed(entry.assetId)) return
        upgradeStill(entry.media) {
            if (rowId(entry.assetId) != null) publish(entry)
        }

        val program = buildProgram(entry)''')
patch(publisher,
      '''        val storageKey = NEXT_KEY_PREFIX + seriesKey
        val title = media.seriesTitle?.takeIf(String::isNotBlank) ?: media.title''',
      '''        val storageKey = NEXT_KEY_PREFIX + seriesKey
        upgradeStill(media) {
            if (rowId(storageKey) != null) publishNextEpisode(seriesKey, media)
        }
        val title = media.seriesTitle?.takeIf(String::isNotBlank) ?: media.title''')
patch(publisher,
      '''                val artwork = if (media.type == JoynMediaType.EPISODE) {
                    media.imageUrl ?: media.backdropUrl
                } else {
                    media.backdropUrl ?: media.imageUrl
                }''',
      '''                val artwork = if (media.type == JoynMediaType.EPISODE) {
                    stills.cachedUrl(media.imageUrl) ?: media.imageUrl ?: media.backdropUrl
                } else {
                    media.backdropUrl ?: media.imageUrl
                }''')
patch(publisher,
      '''        val artwork = if (media.type == JoynMediaType.EPISODE) {
            media.imageUrl ?: media.backdropUrl
        } else {
            media.backdropUrl ?: media.imageUrl
        }''',
      '''        val artwork = if (media.type == JoynMediaType.EPISODE) {
            stills.cachedUrl(media.imageUrl) ?: media.imageUrl ?: media.backdropUrl
        } else {
            media.backdropUrl ?: media.imageUrl
        }''')

Path('joyntv/src/test/java/com/andreassamitsch/joyntv/JoynHighResEpisodeArtworkTest.kt').write_text('''package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynHighResEpisodeArtworkTest {
    @Test fun stripsOnlyTheRenditionOfTheSameApiImage() {
        val apiUrl = "https://img.joyn.de/ingest/t_001/i_p3htxmwhu58j_f8fb7582.jpg/profile:nextgen-web-livestill-503x283"
        assertEquals("https://img.joyn.de/ingest/t_001/i_p3htxmwhu58j_f8fb7582.jpg",
            JoynHighResEpisodeArtwork.originalCandidate(apiUrl))
    }

    @Test fun neverRewritesUnrelatedHostsOrAlreadyUnprofiledImages() {
        assertNull(JoynHighResEpisodeArtwork.originalCandidate(
            "https://example.com/ingest/t_001/episode.jpg/profile:nextgen-web-livestill-503x283"))
        assertNull(JoynHighResEpisodeArtwork.originalCandidate(
            "https://img.joyn.de/ingest/t_001/episode.jpg"))
    }
}
''')

doc = Path('docs/JOYNTV_CURRENT_STATE.md')
doc.write_text(doc.read_text() + '''\n\n### Episode-Overview aus verifizierter TMDB-AT-Serie und bestes tatsächliches Joyn-Still (21.09.2026)\n\nI Launcher behält Joyn als alleinige Quelle für Serienidentität, Logo und Artwork. Nur der Episoden-Detailtext darf durch TMDB ersetzt werden, sofern eine eindeutige TMDB-Serie mit identischem Titel, bestätigtem Herkunftsland AT, passender Staffel und tatsächlich existierender Folge gefunden wurde. Mehrdeutige Treffer oder fehlende TMDB-Episodendaten lassen die Joyn-Kurzbeschreibung unverändert.\n\nJoyn TV bevorzugt das größte vom Joyn-GraphQL-Objekt explizit gelieferte LIVE_STILL. Liegt nur eine kleine Profilrendition vor, wird aus *derselben* API-Bild-URL die Original-Bild-URL ohne /profile:-Suffix ermittelt und mit kleinem, begrenztem HTTP-Abruf auf echte Bildabmessungen geprüft. Nur wenn die Bilddatei tatsächlich größer ist, wird das verifizierte Original an Android-TV-Watch-Next übergeben; ansonsten bleibt das API-Bild. Die Bildauflösung (insbesondere 4K) ist dadurch nicht garantiert und muss am konkreten Joyn-Asset geprüft werden. Keine fremden oder geratenen Bild-IDs; keine Änderung der Joyn-Streamingqualität.\n''')
print('Applied verified TMDB episode text + original Joyn still integration')
