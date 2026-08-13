package com.andreassamitsch.ilauncher.ui.discover

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.home.HeroTextScrollSpeed
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import com.andreassamitsch.ilauncher.ui.home.HomeHero
import com.andreassamitsch.ilauncher.ui.home.HomeHeroContent
import com.andreassamitsch.ilauncher.ui.home.mediaHero
import kotlinx.coroutines.delay

private val DiscoveryHorizontalPadding = 38.dp
private val DiscoveryHeroHeight = 360.dp
private val DiscoveryFirstRailTop = 275.dp
private val DiscoveryRailVerticalPadding = 26.dp
private val DiscoveryCardSpacing = 14.dp
private val DiscoveryBottomFocusReserve = 120.dp

/**
 * Deliberate TMDB discovery surface for one media type.
 *
 * The page follows the same visual contract as Home: the hero owns the backdrop layer while the
 * first media rail overlaps its lower edge. Focusing a card updates the hero without moving the
 * row keyline. The top navigation remains a separate overlay owned by LauncherApp.
 */
@Composable
fun ContentDiscoveryScreen(
    title: String,
    subtitle: String,
    sections: List<SearchBrowseSection>,
    isLoading: Boolean,
    tmdbConfigured: Boolean,
    onOpenResult: (SearchItem) -> Unit,
    listState: LazyListState,
    focusRestoreResultId: String?,
    focusRestoreGeneration: Int,
    heroTextScrollSpeed: HeroTextScrollSpeed = HeroTextScrollSpeed.Normal,
    modifier: Modifier = Modifier,
) {
    val restoreRequester = remember(focusRestoreResultId) { FocusRequester() }
    val firstResult = remember(sections) {
        sections.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.media != null }
    }
    var heroResult by remember { mutableStateOf<SearchItem?>(firstResult) }

    LaunchedEffect(sections, firstResult) {
        val currentId = heroResult?.id
        val currentStillExists = currentId != null && sections.any { section ->
            section.items.any { it.id == currentId }
        }
        if (!currentStillExists) heroResult = firstResult
    }

    LaunchedEffect(focusRestoreGeneration, focusRestoreResultId, sections) {
        if (focusRestoreGeneration <= 0 || focusRestoreResultId == null) return@LaunchedEffect
        val sectionIndex = sections.indexOfFirst { section ->
            section.items.any { it.id == focusRestoreResultId }
        }
        if (sectionIndex >= 0) {
            listState.scrollToItem(sectionIndex)
            delay(50)
            runCatching { restoreRequester.requestFocus() }
        }
    }

    val heroContent = heroResult?.media?.let { media ->
        mediaHero(media, sourceLabel = "TMDB").copy(eyebrow = title)
    } ?: HomeHeroContent(
        key = "discovery:$title",
        eyebrow = title,
        title = title,
        description = subtitle,
    )

    Box(modifier = modifier.fillMaxSize()) {
        HomeHero(
            content = heroContent,
            onOpenMediaDetails = { _, _ -> heroResult?.let(onOpenResult) },
            onOpenApp = {},
            onFocused = {},
            textScrollSpeed = heroTextScrollSpeed,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(DiscoveryHeroHeight),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = DiscoveryFirstRailTop)
                .clipToBounds(),
        ) {
            when {
                !tmdbConfigured -> DiscoveryMessage(
                    "TMDB ist nicht konfiguriert. Ohne TMDB-Zugang können diese Inhalte nicht geladen werden.",
                )

                sections.isEmpty() && isLoading -> DiscoveryMessage("TMDB-Inhalte werden geladen …")

                sections.isEmpty() -> DiscoveryMessage("Keine TMDB-Inhalte verfügbar.")

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .touchScrollFallback(listState, Orientation.Vertical),
                    contentPadding = PaddingValues(bottom = DiscoveryBottomFocusReserve),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sections, key = SearchBrowseSection::key) { section ->
                        DiscoveryRow(
                            section = section,
                            focusRestoreResultId = focusRestoreResultId,
                            restoreRequester = restoreRequester,
                            onOpenResult = onOpenResult,
                            onFocused = { heroResult = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = DiscoveryHorizontalPadding, vertical = 24.dp),
    )
}

@Composable
private fun DiscoveryRow(
    section: SearchBrowseSection,
    focusRestoreResultId: String?,
    restoreRequester: FocusRequester,
    onOpenResult: (SearchItem) -> Unit,
    onFocused: (SearchItem) -> Unit,
) {
    val rowState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = DiscoveryHorizontalPadding, top = 5.dp),
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.touchScrollFallback(rowState, Orientation.Horizontal),
            contentPadding = PaddingValues(
                start = DiscoveryHorizontalPadding,
                end = 18.dp,
                top = DiscoveryRailVerticalPadding,
                bottom = DiscoveryRailVerticalPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(DiscoveryCardSpacing),
        ) {
            items(section.items, key = SearchItem::id) { result ->
                val media = result.media ?: return@items
                WatchNextCard(
                    item = media,
                    onClick = { onOpenResult(result) },
                    onFocused = { onFocused(result) },
                    modifier = if (result.id == focusRestoreResultId) {
                        Modifier.focusRequester(restoreRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}
