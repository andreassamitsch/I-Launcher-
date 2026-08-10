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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
    isTmdbLoading: Boolean,
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
            addSection(SearchResultKind.PreviewProgram, "App-Kanäle", "preview")
            addSection(SearchResultKind.EpgProgram, "TV-Programm", "epg")
            addSection(SearchResultKind.App, "Apps", "apps")
        }
    }
    val sections = remember(localSections, tmdbResults, isTmdbLoading, tmdbConfigured, query) {
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

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                modifier = Modifier.size(58.dp),
                scale = CardDefaults.scale(focusedScale = 1.08f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = "Sprachsuche",
                        modifier = Modifier.size(27.dp),
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
            )
        }

        when {
            query.trim().length < 2 -> {
                Text(
                    text = "Filme, Serien, Weiterschauen, App-Kanäle, Apps und TV-Programm durchsuchen.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            sections.isEmpty() && !isTmdbLoading -> {
                Text(
                    text = "Keine Treffer gefunden.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .touchScrollFallback(listState, Orientation.Vertical),
                    contentPadding = PaddingValues(bottom = 24.dp),
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
    val shape = RoundedCornerShape(14.dp)

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
                shape = shape,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isBlank()) {
                        Text(
                            text = "Suchen …",
                            style = MaterialTheme.typography.titleLarge,
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = if (isLoading && section.items.isEmpty()) {
                    "Lädt …"
                } else {
                    "${section.items.size}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (section.items.isEmpty()) {
            Text(
                text = "Online-Treffer werden geladen …",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyRow(
                state = rowState,
                modifier = Modifier.touchScrollFallback(rowState, Orientation.Horizontal),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
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
    val shape = RoundedCornerShape(12.dp)
    TouchCard(
        onClick = onClick,
        modifier = modifier
            .width(270.dp)
            .height(152.dp),
        scale = CardDefaults.scale(focusedScale = 1.045f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            when {
                item.kind == SearchResultKind.App && app != null -> {
                    val icon = remember(app.icon) { app.icon.asImageBitmap() }
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(76.dp),
                    )
                }

                !item.artworkUri.isNullOrBlank() -> {
                    AsyncImage(
                        model = item.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.02f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    item.sourceLabel?.takeIf { it.isNotBlank() },
                    item.subtitle?.takeIf { it.isNotBlank() },
                ).distinct().joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
