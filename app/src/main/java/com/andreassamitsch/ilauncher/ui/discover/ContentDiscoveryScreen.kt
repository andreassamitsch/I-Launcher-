package com.andreassamitsch.ilauncher.ui.discover

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.home.HeroTextScrollSpeed
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.data.tmdb.TmdbDiscoveryPreferences
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.ui.MoviesTopNavigationFocusRequester
import com.andreassamitsch.ilauncher.ui.SeriesTopNavigationFocusRequester
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import com.andreassamitsch.ilauncher.ui.home.HomeHero
import com.andreassamitsch.ilauncher.ui.home.HomeHeroContent
import com.andreassamitsch.ilauncher.ui.home.mediaHero
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val DiscoveryHorizontalPadding = 38.dp
private val DiscoveryHeroHeight = 360.dp
private val DiscoveryFirstRailTop = 275.dp
private val DiscoveryRailVerticalPadding = 26.dp
private val DiscoveryCardSpacing = 14.dp
private val DiscoveryBottomFocusReserve = 120.dp
private const val DiscoveryHeroDetailsDelayMillis = 350L

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
    val context = LocalContext.current
    val loader = LocalTmdbDiscoveryLoader.current
    val prefs = remember(context) { TmdbDiscoveryPreferences(context) }
    val movieKeys by prefs.movieRowKeys.collectAsState()
    val seriesKeys by prefs.seriesRowKeys.collectAsState()
    val mediaType = remember(sections) {
        sections.asSequence().flatMap { it.items.asSequence() }.mapNotNull { it.media?.type }
            .firstOrNull { it == MediaType.Movie || it == MediaType.Series }
    }
    val selectedKeys = when (mediaType) {
        MediaType.Movie -> movieKeys
        MediaType.Series -> seriesKeys
        else -> emptyList()
    }
    val navRequester = when (mediaType) {
        MediaType.Movie -> MoviesTopNavigationFocusRequester
        MediaType.Series -> SeriesTopNavigationFocusRequester
        else -> null
    }
    var selectedSections by remember(mediaType) { mutableStateOf<List<SearchBrowseSection>?>(null) }

    LaunchedEffect(mediaType, selectedKeys, sections, loader) {
        val type = mediaType
        if (type == null || loader == null || selectedKeys.isEmpty() || sections.map { it.key } == selectedKeys) {
            selectedSections = null
        } else {
            selectedSections = runCatching { loader.browse(type, selectedKeys) }
                .getOrDefault(emptyList()).takeIf { it.isNotEmpty() }
        }
    }

    val rows = selectedSections ?: sections
    val restoreRequester = remember(focusRestoreResultId) { FocusRequester() }
    val firstResult = remember(rows) {
        rows.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.media != null }
    }
    var heroResult by remember { mutableStateOf<SearchItem?>(firstResult) }
    var heroMedia by remember { mutableStateOf(firstResult?.media) }

    LaunchedEffect(rows, firstResult) {
        if (rows.none { row -> row.items.any { it.id == heroResult?.id } }) heroResult = firstResult
    }
    LaunchedEffect(heroResult?.id, loader) {
        val base = heroResult?.media
        heroMedia = base
        if (base?.tmdbId == null || loader == null) return@LaunchedEffect
        loader.peekDetails(base)?.let { heroMedia = it; return@LaunchedEffect }
        delay(DiscoveryHeroDetailsDelayMillis)
        heroMedia = runCatching { loader.loadDetails(base) }.getOrDefault(base)
    }
    LaunchedEffect(focusRestoreGeneration, focusRestoreResultId, rows) {
        if (focusRestoreGeneration <= 0 || focusRestoreResultId == null) return@LaunchedEffect
        val rowIndex = rows.indexOfFirst { row -> row.items.any { it.id == focusRestoreResultId } }
        if (rowIndex >= 0) {
            listState.scrollToItem(rowIndex)
            delay(50)
            runCatching { restoreRequester.requestFocus() }
        }
    }

    val hero = heroMedia?.let { media ->
        mediaHero(
            item = media,
            sourceLabel = "TMDB",
            artworkOverride = (media.heroBackdropUri ?: media.backdropUri)?.let { it to false },
        ).copy(eyebrow = null)
    } ?: HomeHeroContent(
        key = "discovery:$title",
        eyebrow = title,
        title = title,
        description = subtitle,
    )

    Box(modifier.fillMaxSize()) {
        HomeHero(
            content = hero,
            onOpenMediaDetails = { _, _ -> heroResult?.let(onOpenResult) },
            onOpenApp = {},
            onFocused = {},
            textScrollSpeed = heroTextScrollSpeed,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().height(DiscoveryHeroHeight),
        )
        Box(Modifier.fillMaxSize().padding(top = DiscoveryFirstRailTop).clipToBounds()) {
            when {
                !tmdbConfigured -> DiscoveryMessage("TMDB ist nicht konfiguriert. Ohne TMDB-Zugang können diese Inhalte nicht geladen werden.")
                rows.isEmpty() && isLoading -> DiscoveryMessage("TMDB-Inhalte werden geladen …")
                rows.isEmpty() -> DiscoveryMessage("Keine TMDB-Inhalte verfügbar.")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().touchScrollFallback(listState, Orientation.Vertical),
                    contentPadding = PaddingValues(bottom = DiscoveryBottomFocusReserve),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = SearchBrowseSection::key) { row ->
                        DiscoveryRow(
                            section = row,
                            restoreResultId = focusRestoreResultId,
                            restoreRequester = restoreRequester,
                            navRequester = navRequester.takeIf { row.key == rows.firstOrNull()?.key },
                            loader = loader,
                            onOpen = onOpenResult,
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
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = DiscoveryHorizontalPadding, vertical = 24.dp),
    )
}

@Composable
private fun DiscoveryRow(
    section: SearchBrowseSection,
    restoreResultId: String?,
    restoreRequester: FocusRequester,
    navRequester: FocusRequester?,
    loader: TmdbDiscoveryLoader?,
    onOpen: (SearchItem) -> Unit,
    onFocused: (SearchItem) -> Unit,
) {
    val rowState = rememberLazyListState()
    var hasFocus by remember(section.key) { mutableStateOf(false) }
    LaunchedEffect(hasFocus, section.items, loader) {
        if (!hasFocus || loader == null) return@LaunchedEffect
        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.map { it.index } }
            .map { visible ->
                if (visible.isEmpty()) emptyList() else {
                    val first = (visible.minOrNull()!! - 1).coerceAtLeast(0)
                    val last = (visible.maxOrNull()!! + 1).coerceAtMost(section.items.lastIndex)
                    (first..last).mapNotNull { section.items.getOrNull(it)?.media }
                }
            }
            .distinctUntilChanged()
            .collectLatest { if (it.isNotEmpty()) loader.prefetchDetails(it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = DiscoveryHorizontalPadding, top = 5.dp),
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.onFocusChanged { hasFocus = it.hasFocus }
                .touchScrollFallback(rowState, Orientation.Horizontal),
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
                var cardModifier = Modifier
                if (result.id == restoreResultId) cardModifier = cardModifier.focusRequester(restoreRequester)
                navRequester?.let { requester -> cardModifier = cardModifier.focusProperties { up = requester } }
                WatchNextCard(
                    item = media,
                    onClick = { onOpen(result) },
                    onFocused = { onFocused(result) },
                    modifier = cardModifier,
                )
            }
        }
    }
}