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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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

    LaunchedEffect(focusRestoreGeneration, focusRestoreResultId) {
        if (focusRestoreGeneration <= 0 || focusRestoreResultId == null) return@LaunchedEffect
        val localIndex = localResults.indexOfFirst { it.id == focusRestoreResultId }
        val tmdbIndex = tmdbResults.indexOfFirst { it.id == focusRestoreResultId }
        val lazyIndex = when {
            localIndex >= 0 -> 1 + localIndex
            tmdbIndex >= 0 -> {
                val localBlockSize = if (localResults.isNotEmpty()) 1 + localResults.size else 0
                localBlockSize + 1 + tmdbIndex
            }
            else -> -1
        }
        if (lazyIndex >= 0) {
            listState.scrollToItem(lazyIndex)
            delay(40)
            runCatching { restoreRequester.requestFocus() }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Globale Suche",
            style = MaterialTheme.typography.headlineLarge,
        )

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
                        modifier = Modifier.size(28.dp),
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
                    text = "Suche nach Filmen, Serien, Apps und TV-Inhalten.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            localResults.isEmpty() && tmdbResults.isEmpty() && !isTmdbLoading -> {
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (localResults.isNotEmpty()) {
                        item(key = "local-heading") {
                            Text(
                                text = "Auf diesem TV · ${localResults.size} Treffer",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        items(localResults, key = SearchItem::id) { item ->
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

                    if (tmdbConfigured && query.trim().length >= 3 && (tmdbResults.isNotEmpty() || isTmdbLoading)) {
                        item(key = "tmdb-heading") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "TMDB",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                if (isTmdbLoading) {
                                    Text(
                                        text = "Online-Treffer werden geladen …",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(tmdbResults, key = SearchItem::id) { item ->
                            SearchResultCard(
                                item = item,
                                app = null,
                                onClick = { onOpenResult(item) },
                                modifier = if (item.id == focusRestoreResultId) {
                                    Modifier.focusRequester(restoreRequester)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }

                    item(key = "bottom-space") {
                        Spacer(Modifier.height(24.dp))
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
    val shape = RoundedCornerShape(12.dp)

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
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
                shape = shape,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isBlank()) {
                    Text(
                        text = "Film, Serie, App oder TV-Sendung suchen …",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SearchResultCard(
    item: SearchItem,
    app: InstalledApp?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TouchCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp),
        scale = CardDefaults.scale(focusedScale = 1.015f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SearchArtwork(item = item, app = app)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val metadata = listOfNotNull(
                    resultKindLabel(item.kind),
                    item.sourceLabel?.takeIf { it.isNotBlank() },
                    item.subtitle?.takeIf { it.isNotBlank() },
                ).distinct().joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchArtwork(
    item: SearchItem,
    app: InstalledApp?,
) {
    val modifier = Modifier
        .size(width = 148.dp, height = 84.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)

    when {
        item.kind == SearchResultKind.App && app != null -> {
            val icon = remember(app.icon) { app.icon.asImageBitmap() }
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                )
            }
        }

        !item.artworkUri.isNullOrBlank() -> {
            AsyncImage(
                model = item.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }

        else -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = resultKindLabel(item.kind),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun resultKindLabel(kind: SearchResultKind): String = when (kind) {
    SearchResultKind.App -> "App"
    SearchResultKind.WatchNext -> "Weiterschauen"
    SearchResultKind.PreviewProgram -> "App-Kanal"
    SearchResultKind.EpgProgram -> "TV-Programm"
    SearchResultKind.Tmdb -> "TMDB"
}
