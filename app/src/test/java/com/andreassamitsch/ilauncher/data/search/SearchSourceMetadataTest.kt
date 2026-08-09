package com.andreassamitsch.ilauncher.data.search

import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SearchResultKind
import com.andreassamitsch.ilauncher.model.WatchNextItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSourceMetadataTest {
    private val repository = SearchRepository()

    @Test
    fun `finds Watch Next show by raw source title when enriched title differs`() {
        val source = WatchNextItem(
            id = 7L,
            sourceOrder = 0,
            packageName = "example.cloudstream",
            programType = null,
            title = "Fallout",
            releaseDate = null,
            seasonDisplayNumber = "2",
            episodeDisplayNumber = "4",
            episodeTitle = "The Demon in the Snow",
            shortDescription = "Eine Folge aus Fallout.",
            posterArtUri = null,
            thumbnailUri = null,
            logoUri = null,
            intentUri = null,
            durationMillis = null,
            playbackPositionMillis = null,
            watchNextType = null,
            lastEngagementTimeUtcMillis = null,
        )
        val enriched = EnrichedWatchNextItem(
            sourceItem = source,
            media = MediaItem(
                id = "enriched-7",
                type = MediaType.Episode,
                title = "The Demon in the Snow",
                subtitle = "S2 E4",
                source = MediaSource(
                    provider = "android-watch-next",
                    sourceId = "7",
                    packageName = source.packageName,
                ),
            ),
        )

        val results = repository.searchLocal(
            query = "Fallout",
            apps = emptyList(),
            watchNextItems = listOf(enriched),
            previewChannels = emptyList(),
            liveTvChannels = emptyList(),
            epgState = EpgState(),
        )

        assertEquals(1, results.size)
        assertEquals(SearchResultKind.WatchNext, results.single().kind)
    }

    @Test
    fun `finds preview programmes by channel title`() {
        val channel = AppContentChannel(
            id = "channel-1",
            sourceOrder = 0,
            packageName = "example.app",
            title = "Meine Empfehlungen",
            appLinkIntentUri = null,
            programs = listOf(
                AppContentProgram(
                    sourceOrder = 0,
                    media = MediaItem(
                        id = "preview-1",
                        type = MediaType.Series,
                        title = "Unrelated title",
                        source = MediaSource(
                            provider = "android-preview",
                            sourceId = "preview-1",
                            packageName = "example.app",
                        ),
                    ),
                ),
            ),
        )

        val results = repository.searchLocal(
            query = "Empfehlungen",
            apps = emptyList(),
            watchNextItems = emptyList(),
            previewChannels = listOf(channel),
            liveTvChannels = emptyList(),
            epgState = EpgState(),
        )

        assertEquals(1, results.size)
        assertEquals(SearchResultKind.PreviewProgram, results.single().kind)
    }
}
