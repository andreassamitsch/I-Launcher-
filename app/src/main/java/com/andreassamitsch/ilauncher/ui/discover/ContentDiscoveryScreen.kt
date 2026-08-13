package com.andreassamitsch.ilauncher.ui.discover

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import kotlinx.coroutines.delay

private val DiscoveryHorizontalPadding = 38.dp
private val DiscoveryRailVerticalPadding = 18.dp

/**
 * Dedicated TMDB discovery surface used by the top-level Movies and Series destinations.
 *
 * Home remains Local First and personal. This screen is intentionally the place where a user asks
 * I Launcher to spend network work on finding something new: trends, popularity, ratings and
 * curated TMDB genre rows. Cards reuse the common media focus language and detail flow.
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
    modifier: Modifier = Modifier,
) {
    val restoreRequester = remember(focusRestoreResultId) { FocusRequester() }
    // The same TMDB title can legitimately appear in several discovery rows. Attach the single
    // FocusRequester only to its first occurrence so returning from details stays deterministic.
    val restoreSectionKey = remember(sections, focusRestoreResultId) {
        focusRestoreResultId?.let { resultId ->
            sections.firstOrNull { section -> section.items.any { it.id == resultId } }?.key
        }
    }

    LaunchedEffect(focusRestoreGeneration, focusRestoreResultId, restoreSectionKey, sections) {
        if (focusRestoreGeneration <= 0 || focusRestoreResultId == null || restoreSectionKey == null) {
            return@LaunchedEffect
        }
        val sectionIndex = sections.indexOfFirst { it.key == restoreSectionKey }
        if (sectionIndex >= 0) {
            // Header occupies item 0 in the vertical list.
            listState.scrollToItem(sectionIndex + 1)
            delay(60)
            runCatching { restoreRequester.requestFocus() }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .touchScrollFallback(listState, Orientation.Vertical),
        contentPadding = PaddingValues(top = 12.dp, bottom = 38.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "discovery-header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DiscoveryHorizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (sections.isNotEmpty()) {
            itemsIndexed(
                items = sections,
                key = { _, section -> section.key },
            ) { _, section ->
                DiscoveryRow(
                    section = section,
                    restoreSectionKey = restoreSectionKey,
                    focusRestoreResultId = focusRestoreResultId,
                    restoreRequester = restoreRequester,
                    onOpenResult = onOpenResult,
                )
            }
        } else {
            item(key = "discovery-state") {
                Text(
                    text = when {
                        isLoading -> "Inhalte werden geladen …"
                        !tmdbConfigured -> "TMDB ist in diesem Build nicht konfiguriert."
                        else -> "Aktuell konnten keine Inhalte geladen werden."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = DiscoveryHorizontalPadding,
                        end = DiscoveryHorizontalPadding,
                        top = 20.dp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DiscoveryRow(
    section: SearchBrowseSection,
    restoreSectionKey: String?,
    focusRestoreResultId: String?,
    restoreRequester: FocusRequester,
    onOpenResult: (SearchItem) -> Unit,
) {
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val itemsWithMedia = remember(section.items) { section.items.filter { it.media != null } }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = DiscoveryHorizontalPadding,
                end = 18.dp,
                top = 5.dp,
            ),
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(
                items = itemsWithMedia,
                key = SearchItem::id,
            ) { result ->
                val media = requireNotNull(result.media)
                val cardModifier = if (
                    section.key == restoreSectionKey && result.id == focusRestoreResultId
                ) {
                    Modifier.focusRequester(restoreRequester)
                } else {
                    Modifier
                }
                WatchNextCard(
                    item = media,
                    onClick = { onOpenResult(result) },
                    modifier = cardModifier,
                )
            }
        }
    }
}
