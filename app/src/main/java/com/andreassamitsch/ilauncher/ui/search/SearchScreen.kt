package com.andreassamitsch.ilauncher.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.R
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.TouchCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import java.util.Locale
import kotlinx.coroutines.delay

private val SearchContentStart = 18.dp
private val SearchCardWidth = 172.dp
private val SearchCardHeight = 97.dp

private data class SearchSection(
    val key: String,
    val title: String,
    val items: List<SearchItem>,
    val online: Boolean = false,
)

private enum class SearchFilter(val label: String) {
    All("Alle"),
    Media("Filme & Serien"),
    Tv("TV"),
    Apps("Apps"),
}

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    localResults: List<SearchItem>,
    tmdbResults: List<SearchItem>,
    browseSections: List<SearchBrowseSection>,
    isTmdbLoading: Boolean,
    isBrowseLoading: Boolean,
    tmdbConfigured: Boolean,
    apps: List<InstalledApp>,
    onOpenResult: (SearchItem) -> Unit,
    listState: LazyListState,
    focusRestoreResultId: String?,
    focusRestoreGeneration: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }
    val restoreRequester = remember(focusRestoreResultId) { FocusRequester() }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var previousQuery by remember { mutableStateOf(query) }
    var activeFilter by remember { mutableStateOf(SearchFilter.All) }

    val localSections = remember(localResults) {
        buildList {
            fun addSection(kind: SearchResultKind, title: String, key: String) {
                localResults.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let {
                    add(SearchSection(key = key, title = title, items = it))
                }
            }
            addSection(SearchResultKind.WatchNext, "Weiterschauen", "watch-next")
            addSection(SearchResultKind.PreviewProgram, "Aus deinen Apps", "preview")
            addSection(SearchResultKind.EpgProgram, "Im TV", "epg")
            addSection(SearchResultKind.App, "Apps", "apps")
        }
    }
    val querySections = remember(localSections, tmdbResults, isTmdbLoading, tmdbConfigured, query) {
        buildList {
            addAll(localSections)
            if (tmdbConfigured && query.trim().length >= 3 && (tmdbResults.isNotEmpty() || isTmdbLoading)) {
                add(
                    SearchSection(
                        key = "tmdb",
                        title = "Filme & Serien",
                        items = tmdbResults,
                        online = true,
                    ),
                )
            }
        }
    }
    val browseUiSections = remember(browseSections) {
        browseSections.map { section ->
            SearchSection(
                key = "browse:${section.key}",
                title = section.title,
                items = section.items,
                online = true,
            )
        }
    }
    val availableFilters = remember(querySections) {
        buildList {
            add(SearchFilter.All)
            if (querySections.any { it.key == "watch-next" || it.key == "preview" || it.key == "tmdb" }) {
                add(SearchFilter.Media)
            }
            if (querySections.any { it.key == "epg" }) add(SearchFilter.Tv)
            if (querySections.any { it.key == "apps" }) add(SearchFilter.Apps)
        }
    }
    val filteredQuerySections = remember(querySections, activeFilter) {
        when (activeFilter) {
            SearchFilter.All -> querySections
            SearchFilter.Media -> querySections.filter { it.key == "watch-next" || it.key == "preview" || it.key == "tmdb" }
            SearchFilter.Tv -> querySections.filter { it.key == "epg" }
            SearchFilter.Apps -> querySections.filter { it.key == "apps" }
        }
    }
    val visibleSections = if (query.isBlank()) browseUiSections else filteredQuerySections

    val voiceSearchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                voiceError = null
                onQueryChange(spoken)
            }
        }
    }

    LaunchedEffect(query) {
        if (query != previousQuery) {
            previousQuery = query
            activeFilter = SearchFilter.All
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(activeFilter) {
        if (query.isNotBlank() && listState.firstVisibleItemIndex > 0) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(focusRestoreGeneration, focusRestoreResultId, visibleSections) {
        if (focusRestoreGeneration <= 0 || focusRestoreResultId == null) return@LaunchedEffect
        val sectionIndex = visibleSections.indexOfFirst { section ->
            section.items.any { it.id == focusRestoreResultId }
        }
        if (sectionIndex >= 0) {
            listState.scrollToItem(sectionIndex)
            delay(50)
            runCatching { restoreRequester.requestFocus() }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SearchContentStart, end = SearchContentStart, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchInput(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.weight(1f),
            )
            TouchCard(
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        voiceError = null
                        voiceSearchLauncher.launch(intent)
                    } else {
                        voiceError = "Sprachsuche ist auf diesem Gerät nicht verfügbar."
                    }
                },
                modifier = Modifier.size(52.dp),
                scale = CardDefaults.scale(focusedScale = 1.04f),
                shape = CardDefaults.shape(shape = RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = "Sprachsuche",
                        modifier = Modifier.size(22.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }

        voiceError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = SearchContentStart),
            )
        }

        if (query.isBlank()) {
            SearchSuggestionArea(onSelect = onQueryChange)
            if (visibleSections.isNotEmpty() || isBrowseLoading) {
                Text(
                    text = "Etwas zum Anschauen finden",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = SearchContentStart, top = 2.dp),
                )
            }
        } else if (availableFilters.size > 1) {
            SearchFilterRow(
                filters = availableFilters,
                activeFilter = activeFilter,
                onSelect = { activeFilter = it },
            )
        }

        when {
            query.isBlank() && visibleSections.isNotEmpty() -> SearchSectionsList(
                sections = visibleSections,
                appsByPackage = appsByPackage,
                isTmdbLoading = false,
                listState = listState,
                focusRestoreResultId = focusRestoreResultId,
                restoreRequester = restoreRequester,
                onOpenResult = onOpenResult,
            )

            query.isBlank() && isBrowseLoading -> Text(
                text = "Inhalte werden geladen …",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SearchContentStart),
            )

            query.isBlank() -> Text(
                text = if (tmdbConfigured) {
                    "Filme und Serien entdecken oder oben direkt suchen."
                } else {
                    "Filme, Serien, Weiterschauen, Apps und TV-Programm durchsuchen."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SearchContentStart),
            )

            query.trim().length < 2 -> Text(
                text = "Mindestens zwei Zeichen eingeben.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SearchContentStart),
            )

            visibleSections.isEmpty() && !isTmdbLoading -> Text(
                text = "Keine Treffer gefunden.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SearchContentStart),
            )

            else -> SearchSectionsList(
                sections = visibleSections,
                appsByPackage = appsByPackage,
                isTmdbLoading = isTmdbLoading,
                listState = listState,
                focusRestoreResultId = focusRestoreResultId,
                restoreRequester = restoreRequester,
                onOpenResult = onOpenResult,
            )
        }
    }
}

@Composable
private fun SearchSuggestionArea(onSelect: (String) -> Unit) {
    val suggestions = remember {
        listOf(
            "Dune",
            "Star Wars",
            "The Last of Us",
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "Sag zum Beispiel",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SearchContentStart, top = 2.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = SearchContentStart, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(suggestions, key = { it }) { suggestion ->
                SearchSuggestionCard(
                    text = suggestion,
                    onClick = { onSelect(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun SearchSuggestionCard(
    text: String,
    onClick: () -> Unit,
) {
    TouchCard(
        onClick = onClick,
        modifier = Modifier
            .width(246.dp)
            .height(82.dp),
        scale = CardDefaults.scale(focusedScale = 1.035f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchFilterRow(
    filters: List<SearchFilter>,
    activeFilter: SearchFilter,
    onSelect: (SearchFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = SearchContentStart, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters, key = { it.name }) { filter ->
            val active = filter == activeFilter
            TouchButton(
                onClick = { onSelect(filter) },
                scale = ButtonDefaults.scale(
                    focusedScale = 1.025f,
                    pressedScale = 0.985f,
                ),
                colors = ButtonDefaults.colors(
                    containerColor = if (active) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.onSurface,
                    focusedContentColor = MaterialTheme.colorScheme.surface,
                    pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                    pressedContentColor = MaterialTheme.colorScheme.surface,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(34.dp)
                    .border(
                        width = 1.dp,
                        color = if (active) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
                        },
                        shape = RoundedCornerShape(50),
                    ),
            ) {
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SearchSectionsList(
    sections: List<SearchSection>,
    appsByPackage: Map<String, InstalledApp>,
    isTmdbLoading: Boolean,
    listState: LazyListState,
    focusRestoreResultId: String?,
    restoreRequester: FocusRequester,
    onOpenResult: (SearchItem) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .touchScrollFallback(listState, Orientation.Vertical),
        contentPadding = PaddingValues(top = 1.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(sections, key = SearchSection::key) { section ->
            SearchResultSection(
                section = section,
                appsByPackage = appsByPackage,
                isLoading = section.online && isTmdbLoading,
                focusRestoreResultId = focusRestoreResultId,
                restoreRequester = restoreRequester,
                onOpenResult = onOpenResult,
            )
        }
    }
}

@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(52.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                },
                shape = shape,
            )
            .padding(horizontal = 18.dp),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(
                        if (focused) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isBlank()) {
                        Text(
                            text = "Filme, Serien, Apps und TV durchsuchen",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun SearchResultSection(
    section: SearchSection,
    appsByPackage: Map<String, InstalledApp>,
    isLoading: Boolean,
    focusRestoreResultId: String?,
    restoreRequester: FocusRequester,
    onOpenResult: (SearchItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = SearchContentStart),
        )

        if (section.items.isEmpty()) {
            Text(
                text = if (isLoading) "Lädt …" else "Keine Einträge",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SearchContentStart),
            )
        } else {
            val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyRow(
                state = rowState,
                modifier = Modifier.touchScrollFallback(rowState, Orientation.Horizontal),
                contentPadding = PaddingValues(horizontal = SearchContentStart, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(section.items, key = SearchItem::id) { item ->
                    SearchResultCard(
                        item = item,
                        app = item.packageName?.let(appsByPackage::get),
                        onClick = { onOpenResult(item) },
                        modifier = if (item.id == focusRestoreResultId) {
                            Modifier.focusRequester(restoreRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    item: SearchItem,
    app: InstalledApp?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember(item.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier.width(SearchCardWidth),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TouchCard(
            onClick = onClick,
            modifier = modifier
                .width(SearchCardWidth)
                .height(SearchCardHeight)
                .onFocusChanged { focused = it.isFocused },
            scale = CardDefaults.scale(focusedScale = 1.045f),
            shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    item.kind == SearchResultKind.App && app != null -> {
                        val icon = remember(app.icon) { app.icon.asImageBitmap() }
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(62.dp),
                        )
                    }

                    !item.artworkUri.isNullOrBlank() -> AsyncImage(
                        model = item.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = listOfNotNull(
            item.sourceLabel?.takeIf { it.isNotBlank() && it != "TMDB" },
            item.subtitle?.takeIf { it.isNotBlank() && it != item.title },
        ).distinct().joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (focused) 0.95f else 0.58f),
            )
        }
    }
}
