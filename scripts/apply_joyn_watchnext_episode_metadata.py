from pathlib import Path


def patch(path: str, old: str, new: str) -> None:
    file = Path(path)
    content = file.read_text()
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one anchor, found {count}: {old[:95]}')
    file.write_text(content.replace(old, new, 1))


root = 'joyntv/src/main/java/com/andreassamitsch/joyntv/'
continue_path = root + 'JoynContinueWatching.kt'
patch(continue_path,
    '            media = media,\n            positionMs = positionMs,\n            durationMs = durationMs,\n            updatedAt = System.currentTimeMillis(),',
    '            media = JoynResumeMediaMerger.merge(media, previous?.media),\n            positionMs = positionMs,\n            durationMs = durationMs,\n            updatedAt = System.currentTimeMillis(),')
patch(continue_path,
    '''        val dirtyById = local.filter { it.dirty }.associateBy { it.assetId }
        val merged = linkedMapOf<String, JoynContinueWatchingEntry>()
        remote.forEach { remoteEntry ->
            if (remoteEntry.assetId !in deleted) {
                merged[remoteEntry.assetId] = dirtyById[remoteEntry.assetId] ?: remoteEntry.copy(dirty = false)
            }
        }''',
    '''        val localById = local.associateBy { it.assetId }
        val dirtyById = local.filter { it.dirty }.associateBy { it.assetId }
        val merged = linkedMapOf<String, JoynContinueWatchingEntry>()
        remote.forEach { remoteEntry ->
            if (remoteEntry.assetId !in deleted) {
                val selected = dirtyById[remoteEntry.assetId] ?: remoteEntry.copy(dirty = false)
                merged[remoteEntry.assetId] = selected.copy(
                    media = JoynResumeMediaMerger.merge(selected.media, localById[remoteEntry.assetId]?.media),
                )
            }
        }''')
patch(continue_path,
    '''                (media.backdropUrl ?: media.imageUrl)?.takeIf(String::isNotBlank)?.let {
                    setPosterArtUri(Uri.parse(it))
                }''',
    '''                val artwork = if (media.type == JoynMediaType.EPISODE) {
                    media.imageUrl ?: media.backdropUrl
                } else {
                    media.backdropUrl ?: media.imageUrl
                }
                artwork?.takeIf(String::isNotBlank)?.let { setPosterArtUri(Uri.parse(it)) }''')
patch(continue_path,
    '''        (media.backdropUrl ?: media.imageUrl)?.takeIf(String::isNotBlank)?.let {
            builder.setPosterArtUri(Uri.parse(it))
        }''',
    '''        val artwork = if (media.type == JoynMediaType.EPISODE) {
            media.imageUrl ?: media.backdropUrl
        } else {
            media.backdropUrl ?: media.imageUrl
        }
        artwork?.takeIf(String::isNotBlank)?.let { builder.setPosterArtUri(Uri.parse(it)) }''')

Path(root + 'JoynResumeMediaMerger.kt').write_text('''package com.andreassamitsch.joyntv

/** Preserve locally known episode metadata when account resume refresh omits it.
 * Only merge items of the same identity; never transfer a different episode's coordinates/artwork. */
internal object JoynResumeMediaMerger {
    fun merge(incoming: JoynMediaItem, previous: JoynMediaItem?): JoynMediaItem {
        if (previous == null || incoming.type != previous.type) return incoming
        val sameVideo = !incoming.videoId.isNullOrBlank() && incoming.videoId == previous.videoId
        if (!sameVideo && incoming.id != previous.id) return incoming
        if (!incoming.videoId.isNullOrBlank() && !previous.videoId.isNullOrBlank() && !sameVideo) {
            return incoming
        }
        val recoveredEpisode = incoming.type == JoynMediaType.EPISODE &&
            (incoming.seasonNumber == null || incoming.episodeNumber == null) &&
            previous.seasonNumber != null && previous.episodeNumber != null
        return incoming.copy(
            videoId = incoming.videoId ?: previous.videoId,
            seasonId = incoming.seasonId ?: previous.seasonId,
            seriesTitle = incoming.seriesTitle ?: previous.seriesTitle,
            seasonNumber = incoming.seasonNumber ?: previous.seasonNumber,
            episodeNumber = incoming.episodeNumber ?: previous.episodeNumber,
            seriesId = incoming.seriesId ?: previous.seriesId,
            seriesPath = incoming.seriesPath ?: previous.seriesPath,
            description = incoming.description ?: previous.description,
            imageUrl = if (recoveredEpisode) previous.imageUrl ?: incoming.imageUrl
                       else incoming.imageUrl ?: previous.imageUrl,
            backdropUrl = if (recoveredEpisode) previous.backdropUrl ?: incoming.backdropUrl
                          else incoming.backdropUrl ?: previous.backdropUrl,
            logoUrl = incoming.logoUrl ?: previous.logoUrl,
        )
    }
}
''')
Path('joyntv/src/test/java/com/andreassamitsch/joyntv/JoynResumeMediaMergerTest.kt').write_text('''package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynResumeMediaMergerTest {
    private val full = JoynMediaItem(
        id = "episode-42", title = "Bauer sucht Frau", type = JoynMediaType.EPISODE,
        videoId = "video-42", seasonNumber = 21, episodeNumber = 4,
        seriesTitle = "Bauer sucht Frau", seasonId = "season-21",
        imageUrl = "https://images.example/episode-42.jpg",
    )

    @Test fun accountResumeDoesNotEraseKnownEpisode() {
        val remote = full.copy(seasonNumber = null, episodeNumber = null, seasonId = null,
            imageUrl = "https://images.example/series.jpg")
        val merged = JoynResumeMediaMerger.merge(remote, full)
        assertEquals(21, merged.seasonNumber)
        assertEquals(4, merged.episodeNumber)
        assertEquals("season-21", merged.seasonId)
        assertEquals("https://images.example/episode-42.jpg", merged.imageUrl)
    }

    @Test fun anotherVideoNeverBorrowsEpisodeCoordinatesOrArtwork() {
        val remote = full.copy(id = "episode-43", videoId = "video-43", seasonNumber = null,
            episodeNumber = null, imageUrl = "https://images.example/episode-43.jpg")
        val merged = JoynResumeMediaMerger.merge(remote, full)
        assertNull(merged.seasonNumber)
        assertNull(merged.episodeNumber)
        assertEquals("https://images.example/episode-43.jpg", merged.imageUrl)
    }

    @Test fun authoritativeIncomingCoordinatesAreNotOverridden() {
        val updated = full.copy(seasonNumber = 22, episodeNumber = 1)
        val merged = JoynResumeMediaMerger.merge(updated, full)
        assertEquals(22, merged.seasonNumber)
        assertEquals(1, merged.episodeNumber)
    }
}
''')

api_path = root + 'JoynApiClient.kt'
patch(api_path,
    '''        val primary = optJSONObject("primaryImage")?.urlValue()
            ?: optJSONObject("thumbnailImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: optJSONObject("heroPortraitImage")?.urlValue()
            ?: optJSONObject("heroPortrait")?.urlValue()
            ?: optJSONArray("images").firstImageUrl()
        val backdrop = optJSONObject("heroLandscapeImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: primary''',
    '''        // Episode thumbnails are usually episode-specific, while series-level hero artwork
        // in lightweight catalogue/resume assets may depict the parent series.
        val episodeThumbnail = if (type == JoynMediaType.EPISODE) {
            optJSONObject("thumbnailImage")?.urlValue()
        } else null
        val primary = episodeThumbnail
            ?: optJSONObject("primaryImage")?.urlValue()
            ?: optJSONObject("thumbnailImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: optJSONObject("heroPortraitImage")?.urlValue()
            ?: optJSONObject("heroPortrait")?.urlValue()
            ?: optJSONArray("images").firstImageUrl()
        val backdrop = episodeThumbnail
            ?: optJSONObject("heroLandscapeImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: primary''')
patch(api_path,
    '''            seasonNumber = optJSONObject("season")?.optInt("number")?.takeIf { it > 0 }
                ?: optJSONObject("season")?.optInt("seasonNumber")?.takeIf { it > 0 },
            episodeNumber = optInt("number").takeIf { it > 0 },''',
    '''            seasonNumber = optJSONObject("season")?.optInt("number")?.takeIf { it > 0 }
                ?: optJSONObject("season")?.optInt("seasonNumber")?.takeIf { it > 0 }
                ?: optInt("seasonNumber").takeIf { it > 0 },
            episodeNumber = optInt("number").takeIf { it > 0 }
                ?: optInt("episodeNumber").takeIf { it > 0 },''')

mapper = 'app/src/main/java/com/andreassamitsch/ilauncher/data/tv/WatchNextMediaMapper.kt'
patch(mapper,
    '''        val resultingType = if (base.type == MediaType.Episode && episode != null) {
            MediaType.Episode
        } else {
            metadata.mediaType
        }''',
    '''        // Android marks an item as an episode even if TMDB resolves only the parent series.
        // Keep its provider episode metadata and allow its episode-specific artwork to win.
        val resultingType = if (base.type == MediaType.Episode) MediaType.Episode else metadata.mediaType''')
patch(mapper,
    '''            episodeStillUri = episode?.stillUri,''',
    '''            episodeStillUri = episode?.stillUri
                ?: base.episodeStillUri
                ?: base.sourceArtworkUri.takeIf { resultingType == MediaType.Episode },''')

doc = Path('docs/JOYNTV_CURRENT_STATE.md')
doc.write_text(doc.read_text() + '''\n\n### Weiterschauen – Episodenmetadaten beim Kontoabgleich bewahren (20.09.2026)\n\nJoyn-ResumeLane-Einträge können Staffel/Folge und Episodenbild weglassen. Für dieselbe Asset-/Video-ID bleiben beim Abspielen bereits ermittelte Staffel-/Folgenangaben und Episode-Artwork nach einem Kontoabgleich erhalten. Der Android-TV-Watch-Next-Eintrag einer Folge bevorzugt das Thumbnail vor einem generischen Serien-Backdrop. Der Joyn-Parser akzeptiert auch direkt gelieferte `seasonNumber` und `episodeNumber`; I Launcher hält einen vom Provider als Episode gekennzeichneten Eintrag als Episode, selbst wenn TMDB zunächst nur die Serie erkennt, und priorisiert dessen Quellbild. Fehlende Episodenangaben werden nicht geraten. Für bereits ohne Koordinaten gespeicherte Resume-Einträge bleibt ein zusätzlicher Test am TV nötig.\n''')
print('Applied Joyn/I Launcher Watch Next metadata repair')
