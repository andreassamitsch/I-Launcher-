#!/usr/bin/env python3
"""One-time, checked migration for Live TV Info rail + EPG episode details.
Removed by its one-time workflow after application.
"""
from pathlib import Path

ROOT = Path('app/src/main/java/com/andreassamitsch/ilauncher')

def change(path, replacements):
    path = ROOT / path
    content = path.read_text(encoding='utf-8')
    for label, before, after in replacements:
        count = content.count(before)
        if count != 1:
            raise RuntimeError(f'{path}: {label}: expected one match, got {count}')
        content = content.replace(before, after, 1)
    path.write_text(content, encoding='utf-8')
    print(f'Updated {path}')

player = 'ui/livetv/LiveTvPlayerScreen.kt'
change(player, [
    ('focus import', 'import androidx.compose.ui.focus.focusRequester\n', 'import androidx.compose.ui.focus.focusRequester\nimport androidx.compose.ui.focus.onFocusChanged\n'),
    ('focused state', '    var showProgramInfo by remember { mutableStateOf(false) }\n', '    var showProgramInfo by remember { mutableStateOf(false) }\n    var infoFocusedServiceReference by remember(currentServiceReference) { mutableStateOf(currentServiceReference) }\n'),
    ('info program', '''    fun resetForChannelChange() {''', '''    val infoChannel = channels.firstOrNull { it.serviceReference == infoFocusedServiceReference } ?: currentChannel
    val infoProgram = infoChannel?.let { channel ->
        val now = System.currentTimeMillis()
        epgState.guide(channel.serviceReference)
            .firstOrNull { now >= it.startUtcMillis && now < it.endUtcMillis }
            ?: channel.now?.takeIf { now >= it.startUtcMillis && now < it.endUtcMillis }
    }

    LaunchedEffect(showProgramInfo, infoChannel?.serviceReference, infoProgram?.startUtcMillis) {
        val channel = infoChannel
        val program = infoProgram
        if (showProgramInfo && channel != null && program != null) {
            onEnrichEpgProgram(channel.serviceReference, program.startUtcMillis)
        }
    }

    fun resetForChannelChange() {'''),
    ('reset focus', '''        showProgramInfo = false
        autoRetryAttempt = 0''', '''        showProgramInfo = false
        infoFocusedServiceReference = currentServiceReference
        autoRetryAttempt = 0'''),
    ('open info', '''    fun openProgramInfo() {
        val channel = currentChannel ?: return
        val program = currentProgram
        if (program == null) {
            overlayVisible = true
            errorMessage = "Für ${channel.name} sind aktuell keine Sendungsinformationen verfügbar."
            return
        }
        onEnrichEpgProgram(channel.serviceReference, program.startUtcMillis)
        showExitConfirmation = false
        channelOverviewPinned = false
        overlayVisible = true
        showProgramInfo = true
    }''', '''    fun openProgramInfo() {
        val channel = currentChannel ?: return
        infoFocusedServiceReference = channel.serviceReference
        showExitConfirmation = false
        channelOverviewPinned = true
        overlayVisible = true
        showProgramInfo = true
    }'''),
    ('info focus', '''            showProgramInfo -> runCatching { programInfoFocusRequester.requestFocus() }''', '''            showProgramInfo -> runCatching { overlayFocusRequester.requestFocus() }'''),
    ('player click', '''                it.setOnClickListener {
                    if (!showEpg && !showExitConfirmation) openChannelOverview()
                }''', '''                it.setOnClickListener {
                    if (!showEpg && !showProgramInfo && !showExitConfirmation) openChannelOverview()
                }'''),
    ('show row while info', '''        if (overlayVisible && !showEpg && !showProgramInfo) {
            currentChannel?.let { channel ->''', '''        if (showProgramInfo && !showEpg) {
            infoChannel?.let { channel ->
                LiveTvProgramHero(
                    channel = channel,
                    program = infoProgram,
                    onClose = {
                        showProgramInfo = false
                        openChannelOverview()
                    },
                    onDetails = {
                        infoProgram?.let { program -> onOpenEpgProgramDetails(channel, program) }
                    },
                    closeFocusRequester = programInfoFocusRequester,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.78f),
                )
            }
        }

        if (overlayVisible && !showEpg) {
            if (!showProgramInfo) currentChannel?.let { channel ->'''),
    ('rail header', '''                    Text("Jetzt im TV", style = MaterialTheme.typography.titleMedium)
                    LazyRow(''', '''                    if (!showProgramInfo) {
                        Text("Jetzt im TV", style = MaterialTheme.typography.titleMedium)
                    }
                    LazyRow('''),
    ('rail focus and selection', '''                                modifier = if (index == currentIndex) {
                                    Modifier.focusRequester(overlayFocusRequester).focusProperties { down = infoButtonFocusRequester }
                                } else Modifier,
                            )''', '''                                modifier = (if (index == currentIndex) {
                                    Modifier.focusRequester(overlayFocusRequester)
                                } else Modifier)
                                    .onFocusChanged { focus ->
                                        if (showProgramInfo && focus.isFocused) {
                                            infoFocusedServiceReference = channel.serviceReference
                                        }
                                    }
                                    .then(
                                        if (index == currentIndex && !showProgramInfo) {
                                            Modifier.focusProperties { down = infoButtonFocusRequester }
                                        } else Modifier,
                                    ),
                            )'''),
    ('hide button rail', '''                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TouchButton(onClick = ::openProgramInfo''', '''                    if (!showProgramInfo) Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TouchButton(onClick = ::openProgramInfo'''),
    ('remove former fullscreen hero', '''        if (showProgramInfo) {
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

''', ''),
])

hero = 'ui/livetv/LiveTvProgramHero.kt'
change(hero, [
    ('animation imports', '''import androidx.compose.foundation.background
''', '''import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
'''),
    ('size imports', '''import androidx.compose.foundation.layout.size
''', '''import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
'''),
    ('runtime imports', '''import androidx.compose.runtime.Composable
''', '''import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
'''),
    ('density import', '''import androidx.compose.ui.layout.ContentScale
''', '''import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
'''),
    ('scroll delay', '''import java.time.format.DateTimeFormatter
''', '''import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
'''),
    ('replace truncated description', '''            program?.let {
                Text(
                    text = it.longDescription?.takeIf(String::isNotBlank)
                        ?: it.shortDescription?.takeIf(String::isNotBlank)
                        ?: "Keine Beschreibung verfügbar.",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }''', '''            program?.let {
                val description = it.longDescription?.takeIf(String::isNotBlank)
                    ?: it.shortDescription?.takeIf(String::isNotBlank)
                    ?: "Keine Beschreibung verfügbar."
                AutoScrollingLiveInfoDescription(
                    key = "${channel.serviceReference}:${it.startUtcMillis}",
                    text = description,
                )
            }'''),
    ('append auto-scroll', '''private fun instantTime(utcMillis: Long): String =''', '''/** Scrolls only when the text exceeds its window; resets when the focused programme changes. */
@Composable
private fun AutoScrollingLiveInfoDescription(key: String, text: String) {
    val scrollState = remember(key, text) { ScrollState(0) }
    val density = LocalDensity.current
    val pixelsPerSecond = with(density) { 30.dp.toPx() }
    LaunchedEffect(key, text, scrollState.maxValue, pixelsPerSecond) {
        val distance = scrollState.maxValue
        if (distance <= 0) return@LaunchedEffect
        val duration = ((distance / pixelsPerSecond) * 1_000f).toInt().coerceIn(3_000, 40_000)
        delay(5_000)
        while (true) {
            scrollState.animateScrollTo(
                distance,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing),
            )
            delay(3_500)
            scrollState.scrollTo(0)
            delay(5_000)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .verticalScroll(scrollState),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun instantTime(utcMillis: Long): String ='''),
])

details = 'ui/details/DetailsScreen.kt'
change(details, [
    ('preserve EPG target', '''        displayItem = detailed

        if (loader != null && detailed.tmdbId != null) {''', '''        displayItem = if (item.source.provider == "epg") {
            detailed.copy(
                seasonNumber = item.seasonNumber ?: detailed.seasonNumber,
                episodeNumber = item.episodeNumber ?: detailed.episodeNumber,
                tmdbEpisodeId = item.tmdbEpisodeId ?: detailed.tmdbEpisodeId,
                source = item.source,
            )
        } else detailed

        if (loader != null && detailed.tmdbId != null) {'''),
    ('EPG requested episode vars', '''    var seasonContentLoading by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, item.type, item.title, item.originalTitle) {''', '''    var seasonContentLoading by remember(item.id) { mutableStateOf(false) }
    val requestedEpgSeason = item.seasonNumber.takeIf {
        item.source.provider == "epg" && item.type == MediaType.Series
    }
    val requestedEpgEpisode = item.episodeNumber.takeIf {
        requestedEpgSeason != null && it != null && it > 0
    }

    LaunchedEffect(item.id, item.type, item.title, item.originalTitle) {'''),
    ('season precedence', '''        val resumeSeason = seriesResume?.seasonNumber?.takeIf { wanted -> loaded.any { it.seasonNumber == wanted } }
        selectedSeasonNumber = resumeSeason
            ?: loaded.firstOrNull { it.seasonNumber > 0 }?.seasonNumber''', '''        val epgSeason = requestedEpgSeason?.takeIf { wanted -> loaded.any { it.seasonNumber == wanted } }
        val resumeSeason = seriesResume?.seasonNumber?.takeIf { wanted -> loaded.any { it.seasonNumber == wanted } }
        selectedSeasonNumber = epgSeason ?: resumeSeason
            ?: loaded.firstOrNull { it.seasonNumber > 0 }?.seasonNumber'''),
    ('ignore resume when EPG episode', '''    LaunchedEffect(seriesResume, seasons, seasonSelectionTouched) {
        if (seasonSelectionTouched) return@LaunchedEffect''', '''    LaunchedEffect(seriesResume, seasons, seasonSelectionTouched, requestedEpgSeason) {
        if (seasonSelectionTouched || requestedEpgSeason != null) return@LaunchedEffect'''),
    ('EPG playback target wins', '''    val seriesPlaybackItem = remember(item, seriesResume) {
        if (item.type == MediaType.Series) seriesPlaybackTarget(item, seriesResume) else item
    }''', '''    val seriesPlaybackItem = remember(item, seriesResume) {
        when {
            item.type == MediaType.Series && requestedEpgSeason != null && requestedEpgEpisode != null -> item
            item.type == MediaType.Series -> seriesPlaybackTarget(item, seriesResume)
            else -> item
        }
    }'''),
    ('pass requested episode', '''                    resume = seriesResume,
                    onSelectSeason = { seasonNumber ->''', '''                    resume = seriesResume,
                    requestedEpgSeason = requestedEpgSeason,
                    requestedEpgEpisode = requestedEpgEpisode,
                    requestedEpgEpisodeId = item.tmdbEpisodeId,
                    onSelectSeason = { seasonNumber ->'''),
    ('series section parameters', '''    resume: SeriesResumePosition?,
    onSelectSeason: (Int) -> Unit,''', '''    resume: SeriesResumePosition?,
    requestedEpgSeason: Int?,
    requestedEpgEpisode: Int?,
    requestedEpgEpisodeId: Int?,
    onSelectSeason: (Int) -> Unit,'''),
    ('episode selection focus', '''            val episodeRowState = rememberLazyListState()
            LazyRow(
                state = episodeRowState,''', '''            val episodeRowState = rememberLazyListState()
            val episodeFocusRequester = remember(requestedEpgSeason, requestedEpgEpisode, requestedEpgEpisodeId) {
                FocusRequester()
            }
            val requestedIndex = if (selectedSeasonNumber == requestedEpgSeason) {
                seasonContent.episodes.indexOfFirst { episode ->
                    (requestedEpgEpisodeId != null && episode.tmdbEpisodeId == requestedEpgEpisodeId) ||
                        (requestedEpgEpisode != null && episode.episodeNumber == requestedEpgEpisode)
                }
            } else -1
            LaunchedEffect(selectedSeasonNumber, seasonContent.episodes, requestedIndex) {
                if (requestedIndex >= 0) {
                    episodeRowState.scrollToItem(requestedIndex)
                    withFrameNanos { }
                    runCatching { episodeFocusRequester.requestFocus() }
                }
            }
            LazyRow(
                state = episodeRowState,'''),
    ('episode row iterate', '''                items(
                    seasonContent.episodes,
                    key = { "episode-${it.tmdbEpisodeId ?: it.id}" },
                ) { episode ->
                    val isResumeEpisode = resume?.let {''', '''                items(
                    seasonContent.episodes,
                    key = { "episode-${it.tmdbEpisodeId ?: it.id}" },
                ) { episode ->
                    val isRequestedEpisode = selectedSeasonNumber == requestedEpgSeason &&
                        ((requestedEpgEpisodeId != null && episode.tmdbEpisodeId == requestedEpgEpisodeId) ||
                            (requestedEpgEpisode != null && episode.episodeNumber == requestedEpgEpisode))
                    val isResumeEpisode = resume?.let {'''),
    ('episode card params', '''                        episode = episode,
                        isResumeEpisode = isResumeEpisode,
                        onClick = { onPlayEpisode(episode) },''', '''                        episode = episode,
                        isResumeEpisode = isResumeEpisode,
                        isRequestedEpisode = isRequestedEpisode,
                        modifier = if (isRequestedEpisode) Modifier.focusRequester(episodeFocusRequester) else Modifier,
                        onClick = { onPlayEpisode(episode) },'''),
    ('episode card signature', '''    isResumeEpisode: Boolean,
    onClick: () -> Unit,
) {
    TouchCard(onClick = onClick) {''', '''    isResumeEpisode: Boolean,
    isRequestedEpisode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TouchCard(onClick = onClick, modifier = modifier) {'''),
    ('episode badge', '''                if (isResumeEpisode) {
                    Text(
                        "WEITERSCHAUEN",''', '''                if (isResumeEpisode || isRequestedEpisode) {
                    Text(
                        if (isRequestedEpisode) "AKTUELLE TV-FOLGE" else "WEITERSCHAUEN",'''),
])

launcher = 'ui/LauncherApp.kt'
change(launcher, [
    ('resolve series detail correctly', '''    type = program.tmdbType ?: MediaType.Unknown,
    title = program.title,''', '''    type = if (
        program.tmdbId != null && program.seasonNumber != null && program.episodeNumber != null &&
        program.tmdbType != MediaType.Movie
    ) MediaType.Series else program.tmdbType ?: MediaType.Unknown,
    title = program.tmdbTitle?.takeIf(String::isNotBlank) ?: program.title,'''),
    ('use enriched event for details', '''                onOpenEpgProgramDetails = { channel, program ->
                    restoreHomeHeroOnDetailsClose = false''', '''                onOpenEpgProgramDetails = { channel, program ->
                    val resolvedProgram = epgState.guide(channel.serviceReference)
                        .firstOrNull { it.startUtcMillis == program.startUtcMillis }
                        ?: program
                    restoreHomeHeroOnDetailsClose = false'''),
    ('resolved media', '''                    selectedHomeDetailsMedia = epgProgramMedia(channel, program)
                    selectedHomeDetailsSourceLabel = channel.name
                    openPlayerEpgInitially = true
                    initialPlayerEpgProgramStartUtcMillis = program.startUtcMillis''', '''                    selectedHomeDetailsMedia = epgProgramMedia(channel, resolvedProgram)
                    selectedHomeDetailsSourceLabel = channel.name
                    openPlayerEpgInitially = true
                    initialPlayerEpgProgramStartUtcMillis = resolvedProgram.startUtcMillis'''),
])
print('One-time migration complete.')
