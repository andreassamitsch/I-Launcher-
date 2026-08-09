package com.andreassamitsch.ilauncher.ui.epg

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun EpgScreen(
    state: EpgState,
    channels: List<LiveTvChannel>,
    selectedServiceReference: String?,
    selectedProgram: LiveTvProgram?,
    onSelectChannel: (String) -> Unit,
    onSelectProgram: (serviceReference: String, program: LiveTvProgram) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    channelListState: LazyListState = rememberLazyListState(),
    programListState: LazyListState = rememberLazyListState(),
) {
    val selectedChannel = channels.firstOrNull { it.serviceReference == selectedServiceReference }
        ?: channels.firstOrNull()
    val guide = state.guide(selectedChannel?.serviceReference)
    val nowUtcMillis = System.currentTimeMillis()
    val selectedProgramStart = selectedProgram
        ?.takeIf { it in guide }
        ?.startUtcMillis
    val selectedProgramFocusRequester = remember(selectedProgramStart) { FocusRequester() }

    LaunchedEffect(
        selectedChannel?.serviceReference,
        guide.firstOrNull()?.startUtcMillis,
        guide.lastOrNull()?.startUtcMillis,
        selectedProgramStart,
    ) {
        val targetIndex = targetProgramIndex(
            programmes = guide,
            nowUtcMillis = System.currentTimeMillis(),
            selectedProgramStartUtcMillis = selectedProgramStart,
        )
        if (targetIndex >= 0) {
            programListState.scrollToItem(targetIndex)
        }

        if (selectedProgramStart != null && targetIndex >= 0) {
            val channelIndex = channels.indexOfFirst {
                it.serviceReference == selectedChannel?.serviceReference
            }
            if (channelIndex >= 0) {
                channelListState.scrollToItem(channelIndex)
            }
            delay(50)
            runCatching { selectedProgramFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("EPG", style = MaterialTheme.typography.displaySmall)
                Text(
                    text = "XMLTV-Anreicherung · ${state.sourceLabel}" +
                        (state.epgLabel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRefresh, enabled = !state.isRefreshing && channels.isNotEmpty()) {
                Text(if (state.isRefreshing) "Aktualisiere …" else "EPG aktualisieren")
            }
        }

        state.errorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (channels.isEmpty()) {
            Text(
                "Zuerst im Bereich Live TV eine Gigablue verbinden und ein Bouquet auswählen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Sender", style = MaterialTheme.typography.headlineSmall)
                LazyColumn(
                    state = channelListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(channels, key = LiveTvChannel::serviceReference) { channel ->
                        Button(
                            onClick = { onSelectChannel(channel.serviceReference) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (channel.serviceReference == selectedChannel?.serviceReference) {
                                    "✓ ${channel.name}"
                                } else {
                                    channel.name
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    selectedChannel?.name ?: "Programm",
                    style = MaterialTheme.typography.headlineSmall,
                )

                selectedProgram?.takeIf {
                    selectedChannel != null && it in guide
                }?.let { program ->
                    ProgramDetails(program)
                }

                if (guide.isEmpty()) {
                    Text(
                        "Für diesen Sender sind noch keine zugeordneten XMLTV-Programmdaten vorhanden.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = programListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = guide,
                            key = { "${it.xmltvChannelId}:${it.startUtcMillis}" },
                        ) { program ->
                            val isCurrent = nowUtcMillis >= program.startUtcMillis &&
                                nowUtcMillis < program.endUtcMillis
                            val isSelected = program.startUtcMillis == selectedProgramStart
                            Button(
                                onClick = {
                                    selectedChannel?.let { channel ->
                                        onSelectProgram(channel.serviceReference, program)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isSelected) {
                                            Modifier.focusRequester(selectedProgramFocusRequester)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        buildString {
                                            if (isCurrent) append("JETZT · ")
                                            append(formatTime(program.startUtcMillis))
                                            append("–")
                                            append(formatTime(program.endUtcMillis))
                                            append(" · ")
                                            append(program.title)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    program.subtitle?.let { subtitle ->
                                        Text(
                                            subtitle,
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
                }
            }
        }
    }
}

internal fun targetProgramIndex(
    programmes: List<LiveTvProgram>,
    nowUtcMillis: Long,
    selectedProgramStartUtcMillis: Long?,
): Int {
    if (programmes.isEmpty()) return -1
    selectedProgramStartUtcMillis?.let { selectedStart ->
        programmes.indexOfFirst { it.startUtcMillis == selectedStart }
            .takeIf { it >= 0 }
            ?.let { return it }
    }
    return initialProgramIndex(programmes, nowUtcMillis)
}

internal fun initialProgramIndex(
    programmes: List<LiveTvProgram>,
    nowUtcMillis: Long,
): Int {
    if (programmes.isEmpty()) return -1
    val currentIndex = programmes.indexOfFirst { programme ->
        nowUtcMillis >= programme.startUtcMillis && nowUtcMillis < programme.endUtcMillis
    }
    if (currentIndex >= 0) return currentIndex

    val nextIndex = programmes.indexOfFirst { it.startUtcMillis >= nowUtcMillis }
    return if (nextIndex >= 0) nextIndex else programmes.lastIndex
}

@Composable
private fun ProgramDetails(program: LiveTvProgram) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        program.preferredArtworkUri?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(260.dp)
                    .height(146.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(program.title, style = MaterialTheme.typography.titleLarge)
            program.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(
                "${formatTime(program.startUtcMillis)}–${formatTime(program.endUtcMillis)}" +
                    episodeLabel(program),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            program.categories.orEmpty().takeIf { it.isNotEmpty() }?.let { categories ->
                Text(
                    categories.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val description = program.longDescription ?: program.shortDescription
            description?.let { ScrollableDescription(it) }
        }
    }
}

@Composable
private fun ScrollableDescription(text: String) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 104.dp)
            .verticalScroll(scrollState)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        if (!scrollState.canScrollForward) return@onPreviewKeyEvent false
                        scope.launch { scrollState.animateScrollTo((scrollState.value + 80).coerceAtMost(scrollState.maxValue)) }
                        true
                    }
                    Key.DirectionUp -> {
                        if (!scrollState.canScrollBackward) return@onPreviewKeyEvent false
                        scope.launch { scrollState.animateScrollTo((scrollState.value - 80).coerceAtLeast(0)) }
                        true
                    }
                    else -> false
                }
            },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun episodeLabel(program: LiveTvProgram): String = when {
    program.seasonNumber != null && program.episodeNumber != null ->
        " · S${program.seasonNumber}:E${program.episodeNumber}"
    else -> ""
}

private fun formatTime(utcMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMillis))
