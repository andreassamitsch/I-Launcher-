"""One-time, assertion-checked Live-TV info-hero migration; removed after application."""
from pathlib import Path

screen_path = Path("app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvPlayerScreen.kt")
screen = screen_path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global screen
    count = screen.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one occurrence, found {count}")
    screen = screen.replace(old, new, 1)


replace_once(
    '                if (showEpg || showProgramInfo || showExitConfirmation) return@onPreviewKeyEvent false',
    '''                if (showProgramInfo && keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_INFO -> {
                            showProgramInfo = false
                            openChannelOverview()
                            return@onPreviewKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                            zap(+1)
                            return@onPreviewKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            zap(-1)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                if (showEpg || showProgramInfo || showExitConfirmation) return@onPreviewKeyEvent false''',
    "remote info toggle and channel change",
)

replace_once(
    '        if (overlayVisible && !showEpg) {',
    '        if (overlayVisible && !showEpg && !showProgramInfo) {',
    "hide upper channel information and bottom overview while hero active",
)

replace_once(
    '''                        TouchButton(onClick = ::openEpg, modifier = Modifier.focusRequester(epgButtonFocusRequester)) {
                            Text("EPG")
                        }
                        TouchButton(onClick = ::openProgramInfo) {
                            Text("Info")
                        }''',
    '''                        TouchButton(onClick = ::openProgramInfo) {
                            Text("Info")
                        }
                        TouchButton(onClick = ::openEpg, modifier = Modifier.focusRequester(epgButtonFocusRequester)) {
                            Text("EPG")
                        }''',
    "info before EPG",
)

start = '        if (showProgramInfo) {\n            val program = currentProgram\n            Box('
end = '        if (showExitConfirmation) {\n            Box('
if screen.count(start) != 1 or screen.count(end) != 1:
    raise RuntimeError("original info dialog / exit confirmation block not found exactly once")
start_index = screen.index(start)
end_index = screen.index(end, start_index)
screen = screen[:start_index] + '''        if (showProgramInfo) {
            currentChannel?.let { channel ->
                LiveTvProgramHero(
                    channel = channel,
                    program = currentProgram,
                    onClose = {
                        showProgramInfo = false
                        openChannelOverview()
                    },
                    onDetails = {
                        currentProgram?.let { program ->
                            onOpenEpgProgramDetails(channel, program)
                        }
                    },
                    closeFocusRequester = programInfoFocusRequester,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

''' + screen[end_index:]
screen_path.write_text(screen, encoding="utf-8")

hero = r'''package com.andreassamitsch.ilauncher.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LIVE_INFO_CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** Full-bleed programme hero matching the launcher's overview, with uninterrupted TV playback behind it. */
@Composable
internal fun LiveTvProgramHero(
    channel: LiveTvChannel,
    program: LiveTvProgram?,
    onClose: () -> Unit,
    onDetails: () -> Unit,
    closeFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    val wideArtwork = program?.backdropUri
        ?: program?.episodeStillUri
        ?: program?.imageUri
    val portraitArtwork = if (wideArtwork == null) program?.posterUri else null

    Box(modifier = modifier.background(background)) {
        wideArtwork?.takeIf(String::isNotBlank)?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        portraitArtwork?.takeIf(String::isNotBlank)?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.TopEnd,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.60f)
                    .fillMaxHeight(0.93f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to background.copy(alpha = 0.99f),
                        0.26f to background.copy(alpha = 0.94f),
                        0.47f to background.copy(alpha = 0.65f),
                        0.68f to background.copy(alpha = 0.18f),
                        1.00f to background.copy(alpha = 0.04f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to background.copy(alpha = 0.03f),
                        0.65f to background.copy(alpha = 0.03f),
                        0.86f to background.copy(alpha = 0.35f),
                        1.00f to background.copy(alpha = 0.96f),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.52f)
                .padding(start = 42.dp, end = 14.dp, top = 52.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            channel.piconUri?.takeIf(String::isNotBlank)?.let { picon ->
                AsyncImage(
                    model = picon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.size(width = 174.dp, height = 65.dp),
                )
            }
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = program?.title ?: "Keine Sendungsinformationen verfügbar",
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val metadata = program?.let {
                buildList {
                    add(instantTime(it.startUtcMillis) + "–" + instantTime(it.endUtcMillis))
                    it.seasonNumber?.let { season -> add("S$season") }
                    it.episodeNumber?.let { episode -> add("F$episode") }
                    it.releaseYear?.let { year -> add(year.toString()) }
                    it.categories.orEmpty().firstOrNull()?.let(::add)
                }.joinToString(" · ")
            }
            metadata?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            program?.subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            program?.let {
                Text(
                    text = it.longDescription?.takeIf(String::isNotBlank)
                        ?: it.shortDescription?.takeIf(String::isNotBlank)
                        ?: "Keine Beschreibung verfügbar.",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TouchButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocusRequester)) {
                    Text("Info schließen")
                }
                if (program != null) {
                    TouchButton(onClick = onDetails) {
                        Text("Details")
                    }
                }
            }
        }
    }
}

private fun instantTime(utcMillis: Long): String = Instant.ofEpochMilli(utcMillis)
    .atZone(ZoneId.systemDefault())
    .format(LIVE_INFO_CLOCK)
'''
hero_path = Path("app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvProgramHero.kt")
if hero_path.exists():
    raise RuntimeError("hero file already exists, refusing overwrite")
hero_path.write_text(hero, encoding="utf-8")
print("Applied Live TV hero and Info-before-EPG changes")
