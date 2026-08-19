package com.andreassamitsch.ilauncher.ui.details

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.PersonDetails
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback

@Composable
internal fun PersonDetailsScreen(
    person: PersonDetails?,
    isLoading: Boolean,
    onBack: () -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    focusRestoreMediaKey: String? = null,
    focusRestoreGeneration: Int = 0,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val backRequester = remember(person?.tmdbId) { FocusRequester() }
    LaunchedEffect(person?.tmdbId, isLoading, focusRestoreMediaKey, focusRestoreGeneration) {
        if (focusRestoreMediaKey != null && focusRestoreGeneration > 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { backRequester.requestFocus() }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(scroll)
            .padding(start = 44.dp, end = 30.dp, top = 30.dp, bottom = 80.dp),
    ) {
        TouchButton(onClick = onBack, modifier = Modifier.focusRequester(backRequester)) { Text("Zurück") }
        Spacer(Modifier.height(24.dp))
        if (person == null) {
            Text(
                if (isLoading) "Informationen werden geladen …" else "Keine Informationen verfügbar.",
                style = MaterialTheme.typography.titleMedium,
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            person.profileUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 190.dp, height = 260.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(person.name, style = MaterialTheme.typography.displaySmall)
                val metadata = buildList {
                    person.knownForDepartment?.let { add(localizedDepartment(it)) }
                    person.birthday?.let { add(person.deathday?.let { d -> "$it – $d" } ?: "geb. $it") }
                    person.placeOfBirth?.let(::add)
                }.joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(metadata, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                person.biography?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth(.72f))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("Filme & Serien mit ${person.name}", style = MaterialTheme.typography.titleMedium)
        if (person.works.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Keine Filmografie verfügbar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val rowState = rememberLazyListState()
            val workRestoreRequester = remember(person.tmdbId) { FocusRequester() }
            LaunchedEffect(person.works, focusRestoreMediaKey, focusRestoreGeneration) {
                val restoreKey = focusRestoreMediaKey ?: return@LaunchedEffect
                if (focusRestoreGeneration <= 0) return@LaunchedEffect
                val targetIndex = person.works.indexOfFirst { detailMediaFocusKey(it) == restoreKey }
                if (targetIndex < 0) return@LaunchedEffect
                rowState.scrollToItem(targetIndex)
                withFrameNanos { }
                runCatching { workRestoreRequester.requestFocus() }
            }
            LazyRow(
                state = rowState,
                modifier = Modifier.fillMaxWidth().touchScrollFallback(rowState, Orientation.Horizontal),
                contentPadding = PaddingValues(top = 22.dp, bottom = 28.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(person.works, key = { "person-work-${it.type}-${it.tmdbId}" }) { media ->
                    val cardModifier = if (detailMediaFocusKey(media) == focusRestoreMediaKey) {
                        Modifier.focusRequester(workRestoreRequester)
                    } else Modifier
                    WatchNextCard(
                        item = media,
                        onClick = { onOpenMedia(media) },
                        modifier = cardModifier,
                    )
                }
            }
        }
    }
}

private fun localizedDepartment(value: String): String = when (value.lowercase()) {
    "acting" -> "Schauspiel"
    "directing" -> "Regie"
    "writing" -> "Drehbuch"
    "production" -> "Produktion"
    else -> value
}
