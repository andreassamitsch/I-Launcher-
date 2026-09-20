from pathlib import Path

ROOT = Path('joyntv/src/main/java/com/andreassamitsch/joyntv')
APP = Path('app/src/main/java/com/andreassamitsch/ilauncher')


def patch(path, old, new):
    path = Path(path)
    original = path.read_text()
    matches = original.count(old)
    if matches != 1:
        raise RuntimeError(f'{path}: expected exactly one anchor, got {matches}: {old[:110]!r}')
    path.write_text(original.replace(old, new, 1))


# Treat the actual Joyn episode, its season and the Joyn series as three separate artwork sources.
path = ROOT / 'JoynModels.kt'
patch(path,
      '    val seriesId: String? = null,\n    val seriesPath: String? = null,\n) {',
      '    val seriesId: String? = null,\n    val seriesPath: String? = null,\n    val seasonArtworkUrl: String? = null,\n    val seriesBackdropUrl: String? = null,\n) {')
patch(path,
      'data class JoynSeason(\n    val id: String,\n    val number: Int,\n    val licenseTypes: Set<String> = emptySet(),\n)',
      'data class JoynSeason(\n    val id: String,\n    val number: Int,\n    val licenseTypes: Set<String> = emptySet(),\n    val artworkUrl: String? = null,\n)')

path = ROOT / 'JoynApiClient.kt'
patch(path,
      '                add(JoynSeason(id, season.optInt("number", index + 1), licenseTypes))',
      '''                val seasonArtwork = season.optJSONObject("heroLandscapeImage")?.urlValue()
                    ?: season.optJSONObject("thumbnailImage")?.urlValue()
                    ?: season.optJSONObject("posterImage")?.urlValue()
                add(JoynSeason(id, season.optInt("number", index + 1), licenseTypes, seasonArtwork))''')

# Persist rich metadata; account-state replacement must not silently discard it.
path = ROOT / 'JoynContinueWatching.kt'
patch(path,
      '            .putNullable("seriesPath", item.seriesPath)\n            .put("licenseTypes",',
      '            .putNullable("seriesPath", item.seriesPath)\n            .putNullable("seasonArtworkUrl", item.seasonArtworkUrl)\n            .putNullable("seriesBackdropUrl", item.seriesBackdropUrl)\n            .put("licenseTypes",')
patch(path,
      '                seriesPath = json.nullableString("seriesPath"),\n            )',
      '                seriesPath = json.nullableString("seriesPath"),\n                seasonArtworkUrl = json.nullableString("seasonArtworkUrl"),\n                seriesBackdropUrl = json.nullableString("seriesBackdropUrl"),\n            )')

# Encode the actual episode still as poster, Austrian series/season art as thumbnail, Austrian
# logo as logo, and episode synopsis as the Android TV short description. Android's TV provider
# already offers separate columns for all of these; avoid a fragile new IPC protocol.
patch(path,
      '''                artwork?.takeIf(String::isNotBlank)?.let { setPosterArtUri(Uri.parse(it)) }
            }
            .build()''',
      '''                artwork?.takeIf(String::isNotBlank)?.let { setPosterArtUri(Uri.parse(it)) }
                (media.seasonArtworkUrl ?: media.seriesBackdropUrl)
                    ?.takeIf(String::isNotBlank)?.let { setThumbnailUri(Uri.parse(it)) }
                media.logoUrl?.takeIf(String::isNotBlank)?.let { setLogoUri(Uri.parse(it)) }
            }
            .build()''')
patch(path,
      '''            .setDescription(episodeText ?: media.description)
            .setIntentUri(watchNextUri(entry.assetId))''',
      '''            .setDescription(media.description?.takeIf(String::isNotBlank) ?: episodeText)
            .setIntentUri(watchNextUri(entry.assetId))''')
patch(path,
      '''        artwork?.takeIf(String::isNotBlank)?.let { builder.setPosterArtUri(Uri.parse(it)) }
        return builder.build()''',
      '''        artwork?.takeIf(String::isNotBlank)?.let { builder.setPosterArtUri(Uri.parse(it)) }
        if (media.type == JoynMediaType.EPISODE) {
            (media.seasonArtworkUrl ?: media.seriesBackdropUrl)
                ?.takeIf(String::isNotBlank)?.let { builder.setThumbnailUri(Uri.parse(it)) }
        }
        media.logoUrl?.takeIf(String::isNotBlank)?.let { builder.setLogoUri(Uri.parse(it)) }
        return builder.build()''')

# Existing series detail screen has exactly the series and season records shown on the real TV.
# Pass those Austrian images and logos into playback, not just the episode video ID.
path = ROOT / 'SeriesActivity.kt'
patch(path,
      '''                    seriesPath = episode.seriesPath ?: series.path,
                )''',
      '''                    seriesPath = episode.seriesPath ?: series.path,
                    logoUrl = series.logoUrl ?: episode.logoUrl,
                    seriesBackdropUrl = series.backdropUrl ?: series.imageUrl,
                    seasonArtworkUrl = season.artworkUrl ?: episode.seasonArtworkUrl,
                    seasonNumber = episode.seasonNumber ?: season.number,
                )''')

path = ROOT / 'PlayerActivity.kt'
patch(path,
      '''        private const val EXTRA_MEDIA_SERIES_PATH = "joyn_media_series_path"
        private const val EXTRA_MEDIA_SEASON_NUMBER''',
      '''        private const val EXTRA_MEDIA_SERIES_PATH = "joyn_media_series_path"
        private const val EXTRA_MEDIA_SERIES_BACKDROP = "joyn_media_series_backdrop"
        private const val EXTRA_MEDIA_SEASON_ARTWORK = "joyn_media_season_artwork"
        private const val EXTRA_MEDIA_SEASON_NUMBER''')
patch(path,
      '''                item.seriesPath?.let { putExtra(EXTRA_MEDIA_SERIES_PATH, it) }
                item.seasonNumber?.let''',
      '''                item.seriesPath?.let { putExtra(EXTRA_MEDIA_SERIES_PATH, it) }
                item.seriesBackdropUrl?.let { putExtra(EXTRA_MEDIA_SERIES_BACKDROP, it) }
                item.seasonArtworkUrl?.let { putExtra(EXTRA_MEDIA_SEASON_ARTWORK, it) }
                item.seasonNumber?.let''')
patch(path,
      '''        seriesPath = intent.getStringExtra("joyn_media_series_path"),
    )''',
      '''        seriesPath = intent.getStringExtra("joyn_media_series_path"),
        seriesBackdropUrl = intent.getStringExtra("joyn_media_series_backdrop"),
        seasonArtworkUrl = intent.getStringExtra("joyn_media_season_artwork"),
    )''')

path = ROOT / 'JoynResumeMediaMerger.kt'
patch(path,
      '''            logoUrl = incoming.logoUrl ?: previous.logoUrl,
        )''',
      '''            logoUrl = incoming.logoUrl ?: previous.logoUrl,
            seriesBackdropUrl = incoming.seriesBackdropUrl ?: previous.seriesBackdropUrl,
            seasonArtworkUrl = incoming.seasonArtworkUrl ?: previous.seasonArtworkUrl,
        )''')

# Only exact Joyn video/asset identity may supply missing episode details. A title match cannot
# distinguish Bauer sucht Frau Austria from Bauer sucht Frau Germany.
(ROOT / 'JoynExactEpisodeMetadata.kt').write_text('''package com.andreassamitsch.joyntv

internal object JoynExactEpisodeMetadata {
    fun matches(entry: JoynContinueWatchingEntry, candidate: JoynMediaItem): Boolean {
        if (candidate.type != JoynMediaType.EPISODE) return false
        val media = entry.media
        if (!media.videoId.isNullOrBlank() && !candidate.videoId.isNullOrBlank()) {
            return media.videoId == candidate.videoId
        }
        if (media.id == candidate.id) return true
        return entry.assetId == candidate.id || entry.assetId == candidate.videoId
    }

    fun enrich(
        entry: JoynContinueWatchingEntry,
        episode: JoynMediaItem?,
        series: JoynMediaItem?,
        seasonArtworkUrl: String?,
    ): JoynContinueWatchingEntry {
        val trustedEpisode = episode?.takeIf { matches(entry, it) }
        val media = entry.media
        if (trustedEpisode == null && series == null) return entry
        return entry.copy(media = media.copy(
            title = trustedEpisode?.title?.takeIf(String::isNotBlank) ?: media.title,
            description = trustedEpisode?.description?.takeIf(String::isNotBlank)
                ?: media.description?.takeIf { it.isNotBlank() && it != series?.description },
            imageUrl = trustedEpisode?.imageUrl ?: media.imageUrl,
            backdropUrl = trustedEpisode?.backdropUrl ?: media.backdropUrl,
            videoId = trustedEpisode?.videoId ?: media.videoId,
            seasonId = trustedEpisode?.seasonId ?: media.seasonId,
            seasonNumber = trustedEpisode?.seasonNumber ?: media.seasonNumber,
            episodeNumber = trustedEpisode?.episodeNumber ?: media.episodeNumber,
            seriesTitle = trustedEpisode?.seriesTitle ?: media.seriesTitle ?: series?.title,
            seriesId = trustedEpisode?.seriesId ?: media.seriesId ?: series?.id,
            seriesPath = trustedEpisode?.seriesPath ?: media.seriesPath ?: series?.path,
            seasonArtworkUrl = seasonArtworkUrl ?: media.seasonArtworkUrl,
            seriesBackdropUrl = series?.backdropUrl ?: series?.imageUrl ?: media.seriesBackdropUrl,
            logoUrl = series?.logoUrl ?: media.logoUrl,
        ))
    }
}
''')

# Account sync: fetch the authoritative *same* Joyn season and optionally the *same* series path.
# All requests run in the selected Joyn country under the existing network route mutex, so this
# cannot silently replace an Austrian episode with a similarly named German TMDB asset.
path = ROOT / 'JoynRepository.kt'
patch(path,
      '''    suspend fun loadContinueWatching(): List<JoynContinueWatchingEntry> = networkOperationMutex.withLock {
        ensureJoynCountryRouting()
        api.loadContinueWatching()
    }''',
      '''    suspend fun loadContinueWatching(): List<JoynContinueWatchingEntry> = networkOperationMutex.withLock {
        ensureJoynCountryRouting()
        val entries = api.loadContinueWatching()
        val seasonCache = mutableMapOf<String, List<JoynMediaItem>>()
        val seriesCache = mutableMapOf<String, JoynSeriesDetails?>()
        entries.mapIndexed { index, entry ->
            if (index >= 20 || entry.media.type != JoynMediaType.EPISODE) return@mapIndexed entry
            var seriesPath = entry.media.seriesPath
            var details = seriesPath?.let { path ->
                seriesCache.getOrPut(path) {
                    runCatching {
                        api.loadSeriesDetails(
                            JoynMediaItem(
                                id = entry.media.seriesId ?: path,
                                title = entry.media.seriesTitle ?: entry.media.title,
                                path = path,
                                type = JoynMediaType.SERIES,
                            ),
                        )
                    }.getOrNull()
                }
            }
            val seasonId = entry.media.seasonId ?: details?.seasons
                ?.firstOrNull { it.number == entry.media.seasonNumber }?.id
            val candidate = seasonId?.let { id ->
                val seasonEpisodes = seasonCache.getOrPut(id) {
                    runCatching { api.loadSeasonEpisodes(id) }.getOrDefault(emptyList())
                }
                seasonEpisodes.firstOrNull { JoynExactEpisodeMetadata.matches(entry, it) }
            }
            if (details == null) {
                seriesPath = candidate?.seriesPath
                details = seriesPath?.let { path ->
                    seriesCache.getOrPut(path) {
                        runCatching {
                            api.loadSeriesDetails(
                                JoynMediaItem(
                                    id = candidate.seriesId ?: path,
                                    title = candidate.seriesTitle ?: entry.media.seriesTitle ?: entry.media.title,
                                    path = path,
                                    type = JoynMediaType.SERIES,
                                ),
                            )
                        }.getOrNull()
                    }
                }
            }
            val seasonArtwork = details?.seasons?.firstOrNull { season ->
                season.id == seasonId || (entry.media.seasonNumber != null &&
                    season.number == entry.media.seasonNumber)
            }?.artworkUrl
            JoynExactEpisodeMetadata.enrich(entry, candidate, details?.series, seasonArtwork)
        }
    }''')

# In I Launcher, Joyn Watch Next already carries exact episode identifiers/artwork/description.
# Do not overlay it with a TMDB *title match* which can resolve the entirely different DE edition.
path = APP / 'data/tv/WatchNextEnrichmentRepository.kt'
patch(path,
      '''    suspend fun enrichMediaOne(media: MediaItem): MediaItem {
        if (!isTmdbConfigured) return media''',
      '''    suspend fun enrichMediaOne(media: MediaItem): MediaItem {
        if (media.source.packageName == JOYN_TV_PACKAGE) return media
        if (!isTmdbConfigured) return media''')
patch(path,
      '''    companion object {
        private const val MAX_PARALLEL_LOOKUPS = 2''',
      '''    companion object {
        private const val JOYN_TV_PACKAGE = "com.andreassamitsch.joyntv"
        private const val MAX_PARALLEL_LOOKUPS = 2''')

path = APP / 'data/tv/WatchNextMediaMapper.kt'
patch(path,
      '''        return MediaItem(
            id = "watch-next:''',
      '''        val joynEpisode = item.packageName == "com.andreassamitsch.joyntv" && type == MediaType.Episode
        return MediaItem(
            id = "watch-next:''')
patch(path,
      '''            logoUri = item.logoUri,
            sourceArtworkUri = item.artworkUri,
            durationMillis''',
      '''            logoUri = item.logoUri,
            episodeStillUri = item.posterArtUri.takeIf { joynEpisode },
            heroBackdropUri = item.thumbnailUri.takeIf { joynEpisode },
            sourceArtworkUri = if (joynEpisode) item.posterArtUri ?: item.artworkUri else item.artworkUri,
            durationMillis''')

path = APP / 'ui/home/HomeScreen.kt'
patch(path,
      '''internal fun watchNextCardArtwork(item: MediaItem, mode: WatchNextArtworkMode): String? {
    if (item.type != MediaType.Episode) return item.preferredArtworkUri''',
      '''internal fun watchNextCardArtwork(item: MediaItem, mode: WatchNextArtworkMode): String? {
    if (item.type != MediaType.Episode) return item.preferredArtworkUri
    if (item.source.packageName == "com.andreassamitsch.joyntv") {
        return when (mode) {
            WatchNextArtworkMode.Episode -> item.episodeStillUri ?: item.sourceArtworkUri
                ?: item.heroBackdropUri
            WatchNextArtworkMode.Series -> item.heroBackdropUri ?: item.episodeStillUri
                ?: item.sourceArtworkUri
        }
    }''')
patch(path,
      '''    if (item.type != MediaType.Episode) return mediaHeroArtwork(item)

    if (item.tmdbId != null) {''',
      '''    if (item.type != MediaType.Episode) return mediaHeroArtwork(item)
    if (item.source.packageName == "com.andreassamitsch.joyntv") {
        return when (mode) {
            WatchNextArtworkMode.Episode -> (item.episodeStillUri ?: item.sourceArtworkUri
                ?: item.heroBackdropUri) to false
            WatchNextArtworkMode.Series -> (item.heroBackdropUri ?: item.episodeStillUri
                ?: item.sourceArtworkUri) to false
        }
    }

    if (item.tmdbId != null) {''')

# Prevent regression of identity, independent image fields and provider-priority behaviour.
Path('joyntv/src/test/java/com/andreassamitsch/joyntv/JoynExactEpisodeMetadataTest.kt').write_text('''package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynExactEpisodeMetadataTest {
    private val remote = JoynContinueWatchingEntry(
        assetId = "asset-at", media = JoynMediaItem(
            id = "asset-at", videoId = "video-at", title = "Bauer sucht Frau",
            seriesTitle = "Bauer sucht Frau", type = JoynMediaType.EPISODE,
            seasonId = "season-at-23", seasonNumber = 23, episodeNumber = 3,
        ), positionMs = 180_000L, durationMs = 5_700_000L,
    )

    @Test fun exactAustrianEpisodeProvidesEpisodeAndSeasonArtwork() {
        val episode = remote.media.copy(title = "F3: Die Qual der Wahl zum Hofwochenstart",
            description = "Kurzbeschreibung der österreichischen Folge",
            imageUrl = "https://at.example/episode-still.jpg")
        val series = JoynMediaItem(id = "series-at", title = "Bauer sucht Frau",
            type = JoynMediaType.SERIES, logoUrl = "https://at.example/logo.png",
            backdropUrl = "https://at.example/series-hero.jpg")
        val enriched = JoynExactEpisodeMetadata.enrich(remote, episode, series,
            "https://at.example/season23.jpg").media
        assertEquals("https://at.example/episode-still.jpg", enriched.imageUrl)
        assertEquals("https://at.example/season23.jpg", enriched.seasonArtworkUrl)
        assertEquals("https://at.example/logo.png", enriched.logoUrl)
        assertEquals("Kurzbeschreibung der österreichischen Folge", enriched.description)
        assertEquals(23, enriched.seasonNumber)
        assertEquals(3, enriched.episodeNumber)
    }

    @Test fun similarlyTitledGermanEpisodeCannotSupplyArtwork() {
        val german = remote.media.copy(id = "asset-de", videoId = "video-de",
            imageUrl = "https://de.example/episode.jpg")
        val unchanged = JoynExactEpisodeMetadata.enrich(remote, german, null, null).media
        assertNull(unchanged.imageUrl)
    }
}
''')
Path('app/src/test/java/com/andreassamitsch/ilauncher/data/tv/JoynWatchNextSourceMetadataTest.kt').write_text('''package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.WatchNextItem
import com.andreassamitsch.ilauncher.data.home.WatchNextArtworkMode
import com.andreassamitsch.ilauncher.ui.home.watchNextHeroArtwork
import com.andreassamitsch.ilauncher.ui.home.watchNextCardArtwork
import org.junit.Assert.assertEquals
import org.junit.Test

class JoynWatchNextSourceMetadataTest {
    @Test fun austriaSeriesLogoSeasonHeroEpisodeStillAndSynopsisRemainSeparate() {
        val row = WatchNextItem(
            id = 123L, sourceOrder = 0, packageName = "com.andreassamitsch.joyntv",
            programType = android.media.tv.TvContract.PreviewPrograms.TYPE_TV_EPISODE,
            title = "Bauer sucht Frau", releaseDate = null, seasonDisplayNumber = "23",
            episodeDisplayNumber = "3", episodeTitle = "F3: Die Qual der Wahl",
            shortDescription = "Die österreichische Episodenbeschreibung",
            posterArtUri = "https://at.example/episode.jpg",
            thumbnailUri = "https://at.example/season23.jpg",
            logoUri = "https://at.example/logo.png", intentUri = "intent://at",
            durationMillis = null, playbackPositionMillis = null, watchNextType = null,
            lastEngagementTimeUtcMillis = null,
        )
        val media = WatchNextMediaMapper.base(row)
        assertEquals(MediaType.Episode, media.type)
        assertEquals("https://at.example/logo.png", media.logoUri)
        assertEquals("Die österreichische Episodenbeschreibung", media.overview)
        assertEquals("https://at.example/episode.jpg", watchNextCardArtwork(media, WatchNextArtworkMode.Episode))
        assertEquals("https://at.example/season23.jpg", watchNextHeroArtwork(media, WatchNextArtworkMode.Series).first)
    }
}
''')

path = Path('docs/JOYNTV_CURRENT_STATE.md')
path.write_text(path.read_text() + '''\n\n### Joyn AT: lokalisierte Metadaten im Android-TV-„Weiterschauen“ (20.09.2026)\n\nBei gleichnamigen Serien wie „Bauer sucht Frau“ (AT/DE) ist TMDB-Titelauflösung für Joyn-Watch-Next-Einträge nicht autoritativ. Joyn TV verknüpft Resume-Episoden nur über exakte Joyn-Video-/Asset-ID mit der Staffel des aktuellen Joyn-Marktes; falls der Episoden-Eintrag eine Joyn-Serien-Path liefert, kommt der Serien-/Staffel-Hero samt Logo aus exakt diesem Joyn-Serienobjekt. Folgenbild (Poster URI), Serien-/Staffelmotiv (Thumbnail URI), Serienlogo (Logo URI), Episodenbeschreibung (SHORT_DESCRIPTION) und S/E werden getrennt im regulären Android-TvProvider-Watch-Next-Datensatz publiziert. I Launcher überspringt für Joyn-Watch-Next-Einträge die unsichere TMDB-Titelanreicherung und wählt das Quellbild nach Nutzer-Modus Episode/Serie. Vorhandene Joyn-Resume-Metadaten bleiben beim Kontoabgleich erhalten. Nur bei vorhandenen Joyn-Daten werden Folge/Artwork angezeigt; keine bloß titelbasierte Vermutung, welche deutsche oder österreichische Serie vorliegt. Die tatsächliche Ausgabe für S23/F3 ist noch auf dem TCL zu verifizieren.\n''')
print('Applied Joyn AT episode/season/series metadata pipeline patch.')
