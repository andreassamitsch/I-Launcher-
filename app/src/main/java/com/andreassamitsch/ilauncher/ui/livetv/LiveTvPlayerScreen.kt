package com.andreassamitsch.ilauncher.ui.livetv

import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifResolvedStream
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifStreamHttpException
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.TouchCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import com.andreassamitsch.ilauncher.ui.epg.EpgScreen
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val PLAYER_OVERLAY_TIMEOUT_MILLIS = 3_000L
private const val LONG_OK_THRESHOLD_MILLIS = 650L
private const val LIVE_TV_PLAYER_TAG = "LIVE_TV_PLAYER"
private val LIVE_TV_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(UnstableApi::class)
@Composable
internal fun LiveTvPlayerScreen(
    channels: List<LiveTvChannel>,
    initialServiceReference: String,
    onResolveStream: suspend (LiveTvChannel) -> OpenWebifResolvedStream,
    epgState: EpgState,
    initialShowEpg: Boolean = false,
    initialEpgProgramStartUtcMillis: Long? = null,
    onRefreshEpg: () -> Unit,
    onEnrichEpgProgram: (serviceReference: String, startUtcMillis: Long) -> Unit,
    onOpenEpgProgramDetails: (LiveTvChannel, LiveTvProgram) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val initialIndex = LiveTvZapping.indexForServiceReference(
        serviceReferences = channels.map(LiveTvChannel::serviceReference),
        currentServiceReference = initialServiceReference,
    )
    var currentServiceReference by remember(initialServiceReference) { mutableStateOf(initialServiceReference) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var channelOverviewPinned by remember { mutableStateOf(false) }
    var confirmOpenedOverview by remember { mutableStateOf(false) }
    var longOkHandled by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var playbackRestartToken by remember { mutableStateOf(0) }
    var preparedServiceReference by remember { mutableStateOf<String?>(null) }
    var autoRetryAttempt by remember(currentServiceReference) { mutableStateOf(0) }
    var retryingPlayback by remember(currentServiceReference) { mutableStateOf(false) }
    var showEpg by remember(initialShowEpg, initialServiceReference) { mutableStateOf(initialShowEpg) }
    var selectedEpgServiceReference by remember(initialServiceReference) { mutableStateOf(initialServiceReference) }
    var selectedEpgProgramStartUtcMillis by remember(initialServiceReference, initialEpgProgramStartUtcMillis) {
        mutableStateOf(initialEpgProgramStartUtcMillis)
    }
    val epgChannelListState = rememberLazyListState()
    val epgProgramListState = rememberLazyListState()
    val zapListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val rootFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }
    val epgButtonFocusRequester = remember { FocusRequester() }
    val epgBackFocusRequester = remember { FocusRequester() }
    val exitConfirmFocusRequester = remember { FocusRequester() }
    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    val currentIndex = LiveTvZapping.indexForServiceReference(
        serviceReferences = channels.map(LiveTvChannel::serviceReference),
        currentServiceReference = currentServiceReference,
    )
    val currentChannel = channels.getOrNull(currentIndex)
    val selectedEpgProgram = selectedEpgProgramStartUtcMillis?.let { start ->
        epgState.guide(selectedEpgServiceReference).firstOrNull { it.startUtcMillis == start }
    }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val nextIndex = LiveTvZapping.nextIndex(currentIndex, channels.size, delta)
        channelOverviewPinned = false
        overlayVisible = true
        showExitConfirmation = false
        autoRetryAttempt = 0
        retryingPlayback = false
        errorMessage = null
        currentServiceReference = channels[nextIndex].serviceReference
    }

    fun selectChannel(index: Int) {
        if (index !in channels.indices) return
        channelOverviewPinned = false
        overlayVisible = true
        showExitConfirmation = false
        autoRetryAttempt = 0
        retryingPlayback = false
        errorMessage = null
        currentServiceReference = channels[index].serviceReference
    }

    fun openChannelOverview() {
        showExitConfirmation = false
        overlayVisible = true
        channelOverviewPinned = true
    }

    fun openEpg() {
        val channel = currentChannel ?: return
        val now = System.currentTimeMillis()
        selectedEpgServiceReference = channel.serviceReference
        selectedEpgProgramStartUtcMillis = epgState.guide(channel.serviceReference)
            .firstOrNull { now >= it.startUtcMillis && now < it.endUtcMillis }
            ?.startUtcMillis
        channelOverviewPinned = false
        showExitConfirmation = false
        showEpg = true
    }

    fun requestExit() {
        showEpg = false
        channelOverviewPinned = false
        overlayVisible = true
        showExitConfirmation = true
    }

    BackHandler {
        when {
            showExitConfirmation -> showExitConfirmation = false
            showEpg -> showEpg = false
            channelOverviewPinned -> {
                channelOverviewPinned = false
                overlayVisible = false
            }
            overlayVisible -> overlayVisible = false
            else -> requestExit()
        }
    }

    LaunchedEffect(channels, currentServiceReference) {
        if (channels.isNotEmpty() && channels.none { it.serviceReference == currentServiceReference }) {
            currentServiceReference = channels.first().serviceReference
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                loading = playbackState == Player.STATE_BUFFERING ||
                    (playbackState == Player.STATE_IDLE && errorMessage == null)
                if (playbackState == Player.STATE_READY) {
                    errorMessage = null
                    retryingPlayback = false
                    autoRetryAttempt = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val failedServiceReference = player.currentMediaItem?.mediaId ?: preparedServiceReference
                if (failedServiceReference != currentServiceReference) {
                    Log.d(
                        LIVE_TV_PLAYER_TAG,
                        "Ignoring playback error from superseded Live-TV item: ${error.errorCodeName}",
                    )
                    return
                }

                if (LiveTvPlaybackRecovery.shouldRetry(error.errorCode, autoRetryAttempt)) {
                    val nextAttempt = autoRetryAttempt + 1
                    autoRetryAttempt = nextAttempt
                    retryingPlayback = true
                    loading = true
                    overlayVisible = true
                    errorMessage = null
                    playbackRestartToken += 1
                    Log.w(
                        LIVE_TV_PLAYER_TAG,
                        "Retrying transient Live-TV parser error ${error.errorCodeName} " +
                            "($nextAttempt/${LiveTvPlaybackRecovery.MAX_AUTO_RETRIES})",
                    )
                    return
                }

                retryingPlayback = false
                loading = false
                overlayVisible = true
                errorMessage = "Live-TV-Wiedergabe fehlgeschlagen (${error.errorCodeName})."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(currentChannel?.serviceReference, playbackRestartToken) {
        val channel = currentChannel ?: return@LaunchedEffect
        val retryAttemptForStart = autoRetryAttempt
        if (!showEpg) {
            selectedEpgServiceReference = channel.serviceReference
            selectedEpgProgramStartUtcMillis = null
        }
        loading = true
        retryingPlayback = retryAttemptForStart > 0
        errorMessage = null
        preparedServiceReference = null
        player.stop()
        player.clearMediaItems()

        if (retryAttemptForStart > 0) {
            delay(LiveTvPlaybackRecovery.retryDelayMillis(retryAttemptForStart))
        }

        try {
            val stream = onResolveStream(channel)
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(6_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(stream.requestHeaders)
            val mediaItem = MediaItem.Builder()
                .setUri(stream.url)
                .setMediaId(channel.serviceReference)
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(channel.name).build())
                .setMimeType(if (stream.isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
                .build()
            val mediaSource = if (stream.isHls) {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }
            preparedServiceReference = channel.serviceReference
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            preparedServiceReference = null
            loading = false
            retryingPlayback = false
            overlayVisible = true
            errorMessage = playbackErrorMessage(throwable)
        }
    }

    LaunchedEffect(currentIndex, channels.size) {
        if (channels.isNotEmpty()) zapListState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
    }

    LaunchedEffect(
        overlayVisible,
        channelOverviewPinned,
        currentChannel?.serviceReference,
        loading,
        errorMessage,
        showEpg,
        showExitConfirmation,
    ) {
        if (
            overlayVisible && !channelOverviewPinned && !loading && errorMessage == null &&
            !showEpg && !showExitConfirmation
        ) {
            delay(PLAYER_OVERLAY_TIMEOUT_MILLIS)
            overlayVisible = false
        }
    }

    LaunchedEffect(
        overlayVisible,
        channelOverviewPinned,
        showEpg,
        showExitConfirmation,
        currentIndex,
        selectedEpgProgramStartUtcMillis,
    ) {
        withFrameNanos { }
        when {
            showExitConfirmation -> runCatching { exitConfirmFocusRequester.requestFocus() }
            showEpg && selectedEpgProgramStartUtcMillis == null -> runCatching { epgBackFocusRequester.requestFocus() }
            showEpg -> Unit
            channelOverviewPinned -> runCatching { overlayFocusRequester.requestFocus() }
            else -> runCatching { rootFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (showEpg || showExitConfirmation) return@onPreviewKeyEvent false
                val nativeEvent = keyEvent.nativeKeyEvent
                val isConfirmKey = nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (isConfirmKey) {
                    when (keyEvent.type) {
                        KeyEventType.KeyDown -> {
                            if (nativeEvent.isLongPress || nativeEvent.repeatCount > 0) {
                                longOkHandled = true
                                confirmOpenedOverview = false
                                openEpg()
                                return@onPreviewKeyEvent true
                            }
                            if (!overlayVisible || !channelOverviewPinned) {
                                openChannelOverview()
                                confirmOpenedOverview = true
                                return@onPreviewKeyEvent true
                            }
                        }

                        KeyEventType.KeyUp -> {
                            val longPress = nativeEvent.eventTime - nativeEvent.downTime >= LONG_OK_THRESHOLD_MILLIS
                            if (longOkHandled || longPress) {
                                if (!longOkHandled) openEpg()
                                longOkHandled = false
                                confirmOpenedOverview = false
                                return@onPreviewKeyEvent true
                            }
                            if (confirmOpenedOverview) {
                                confirmOpenedOverview = false
                                return@onPreviewKeyEvent true
                            }
                        }

                        else -> Unit
                    }
                }
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.ChannelUp -> {
                        zap(+1)
                        true
                    }

                    Key.ChannelDown -> {
                        zap(-1)
                        true
                    }

                    Key.DirectionUp -> if (channelOverviewPinned) {
                        false
                    } else {
                        zap(+1)
                        true
                    }

                    Key.DirectionDown -> if (channelOverviewPinned) {
                        false
                    } else {
                        zap(-1)
                        true
                    }

                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                    isClickable = true
                    setOnClickListener {
                        if (!showEpg && !showExitConfirmation) openChannelOverview()
                    }
                }
            },
            update = {
                it.player = player
                it.setOnClickListener {
                    if (!showEpg && !showExitConfirmation) openChannelOverview()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayVisible && !showEpg) {
            currentChannel?.let { channel ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 20.dp, top = 18.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                            RoundedCornerShape(18.dp),
                        )
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    channel.piconUri?.let { picon ->
                        AsyncImage(
                            model = picon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 92.dp, height = 50.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${currentIndex + 1} · ${channel.name}", style = MaterialTheme.typography.titleLarge)
                        channel.now?.let { Text(it.title, style = MaterialTheme.typography.titleMedium) }
                        channel.next?.let {
                            Text(
                                "${formatLiveTvStartTime(it.startUtcMillis)} · ${it.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        errorMessage?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (loading && errorMessage == null) {
                            Text(
                                if (retryingPlayback) "Stream wird erneut gestartet …" else "Live TV wird geladen …",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (channelOverviewPinned) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Jetzt im TV", style = MaterialTheme.typography.titleMedium)
                    LazyRow(
                        state = zapListState,
                        modifier = Modifier.touchScrollFallback(zapListState, Orientation.Horizontal),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(channels, key = { _, channel -> channel.serviceReference }) { index, channel ->
                            CompactLiveTvCard(
                                channel = channel,
                                channelNumber = index + 1,
                                selected = index == currentIndex,
                                onClick = { selectChannel(index) },
                                modifier = if (index == currentIndex) {
                                    Modifier.focusRequester(overlayFocusRequester).focusProperties { down = epgButtonFocusRequester }
                                } else Modifier,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TouchButton(onClick = ::openEpg, modifier = Modifier.focusRequester(epgButtonFocusRequester)) {
                            Text("EPG")
                        }
                        TouchButton(onClick = ::requestExit) { Text("TV verlassen") }
                        currentChannel?.let {
                            Text(
                                "${currentIndex + 1}/${channels.size} · ${it.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (showEpg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
                    .padding(horizontal = 36.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        currentChannel?.let { "TV-Guide · ${it.name}" } ?: "TV-Guide",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    TouchButton(
                        onClick = { showEpg = false },
                        modifier = Modifier.focusRequester(epgBackFocusRequester),
                    ) {
                        Text("Zurück zum TV")
                    }
                }
                EpgScreen(
                    state = epgState,
                    channels = channels,
                    selectedServiceReference = selectedEpgServiceReference,
                    selectedProgram = selectedEpgProgram,
                    onSelectChannel = { serviceReference ->
                        selectedEpgServiceReference = serviceReference
                        selectedEpgProgramStartUtcMillis = null
                    },
                    onSelectProgram = { serviceReference, program ->
                        selectedEpgServiceReference = serviceReference
                        selectedEpgProgramStartUtcMillis = program.startUtcMillis
                        onEnrichEpgProgram(serviceReference, program.startUtcMillis)
                    },
                    onOpenProgramDetails = { serviceReference, program ->
                        channels.firstOrNull { it.serviceReference == serviceReference }
                            ?.let { onOpenEpgProgramDetails(it, program) }
                    },
                    onRefresh = onRefreshEpg,
                    channelListState = epgChannelListState,
                    programListState = epgProgramListState,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showExitConfirmation) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(460.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                            RoundedCornerShape(18.dp),
                        )
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("Live TV verlassen?", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Die laufende Wiedergabe wird beendet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TouchButton(
                            onClick = onBack,
                            modifier = Modifier.focusRequester(exitConfirmFocusRequester),
                        ) { Text("TV verlassen") }
                        TouchButton(onClick = { showExitConfirmation = false }) { Text("Abbrechen") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactLiveTvCard(
    channel: LiveTvChannel,
    channelNumber: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val artwork = channel.now?.preferredArtworkUri
    TouchCard(
        onClick = onClick,
        modifier = modifier
            .width(184.dp)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        scale = CardDefaults.scale(focusedScale = 1.025f),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !artwork.isNullOrBlank() -> AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    !channel.piconUri.isNullOrBlank() -> AsyncImage(
                        model = channel.piconUri,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )

                    else -> Text(channelNumber.toString(), style = MaterialTheme.typography.headlineSmall)
                }
                if (!artwork.isNullOrBlank() && !channel.piconUri.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.piconUri,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(width = 52.dp, height = 26.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "$channelNumber · ${channel.name}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    channel.now?.title ?: "Keine EPG-Daten",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatLiveTvStartTime(
    startUtcMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = LIVE_TV_TIME_FORMATTER
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(startUtcMillis))

internal object LiveTvZapping {
    fun nextIndex(currentIndex: Int, size: Int, delta: Int): Int {
        if (size <= 0) return 0
        val normalized = currentIndex.coerceIn(0, size - 1)
        return ((normalized + delta) % size + size) % size
    }

    fun indexForServiceReference(
        serviceReferences: List<String>,
        currentServiceReference: String,
    ): Int {
        if (serviceReferences.isEmpty()) return 0
        val index = serviceReferences.indexOf(currentServiceReference)
        return if (index >= 0) index else 0
    }
}

private fun playbackErrorMessage(throwable: Throwable): String = when (throwable) {
    is OpenWebifStreamHttpException -> when (throwable.statusCode) {
        401, 403 -> "Live-TV-Stream: OpenWebif-Authentifizierung fehlgeschlagen."
        else -> "Live-TV-Stream: OpenWebif antwortet mit HTTP ${throwable.statusCode}."
    }

    is UnknownHostException -> "Live-TV-Stream: Receiver-Hostname konnte nicht aufgelöst werden."
    is ConnectException -> "Live-TV-Stream: Gigablue ist nicht erreichbar."
    is SocketTimeoutException -> "Live-TV-Stream: Zeitüberschreitung beim Verbinden."
    else -> "Live-TV-Stream konnte nicht gestartet werden (${throwable.javaClass.simpleName})."
}
