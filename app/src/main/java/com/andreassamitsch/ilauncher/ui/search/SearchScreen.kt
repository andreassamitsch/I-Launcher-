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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.R
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind
import com.andreassamitsch.ilauncher.ui.components.TouchCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import java.util.Locale
import kotlinx.coroutines.delay

private data class SearchSection(
    val key: String,
    val title: String,
    val items: List<SearchItem>,
    val online: Boolean = false,
)

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
    val visibleSections = if (query.isBlank()) browseUiSections else querySections

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
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
                modifier = Modifier.size(44.dp),
                scale = CardDefaults.scale(focusedScale = 1.05f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = "Sprachsuche",
                        modifier = Modifier.size(21.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }

        if (query.isBlank()) {
            Text(
                text = "Entdecken",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        voiceError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp),
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
                text = "Entdecken wird geladen …",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )

            query.isBlank() -> Text(
                text = if (tmdbConfigured) {
                    "Filme und Serien entdecken oder direkt suchen."
                } else {
                    "Filme, Serien, Weiterschauen, Apps und TV-Programm durchsuchen."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )

            query.trim().length < 2 -> Text(
                text = "Mindestens zwei Zeichen eingeben.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )

            visibleSections.isEmpty() && !isTmdbLoading -> Text(
                text = "Keine Treffer gefunden.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
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
        contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
    val shape = RoundedCornerShape(24.dp)

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                },
                shape = shape,
            )
            .padding(horizontal = 17.dp, vertical = 9.dp),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 10.dp),
        )

        if (section.items.isEmpty()) {
            Text(
                text = if (isLoading) "Lädt …" else "Keine Einträge",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        } else {
            val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyRow(
                state = rowState,
                modifier = Modifier.touchScrollFallback(rowState, Orientation.Horizontal),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
    TouchCard(
        onClick = onClick,
        modifier = modifier
            .width(232.dp)
            .onFocusChanged { focused = it.isFocused },
        scale = CardDefaults.scale(focusedScale = 1.028f),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    item.kind == SearchResultKind.App && app != null -> {
                        val icon = remember(app.icon) { app.icon.asImageBitmap() }
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(76.dp),
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

            Column(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
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
                        modifier = Modifier.alpha(if (focused) 1f else 0.55f),
                    )
                }
            }
        }
    }
}
