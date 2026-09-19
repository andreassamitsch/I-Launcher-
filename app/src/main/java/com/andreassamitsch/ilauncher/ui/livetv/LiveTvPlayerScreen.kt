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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.data.joyn.JoynBridgeChannel
import com.andreassamitsch.ilauncher.data.joyn.JoynFallbackPlayback
import com.andreassamitsch.ilauncher.data.joyn.JoynLiveTvFallbackRepository
import com.andreassamitsch.ilauncher.data.livetv.LiveTvJoynFallbackStore
import com.andreassamitsch.ilauncher.data.livetv.LiveTvReceptionMonitor
import com.andreassamitsch.ilauncher.data.livetv.LiveTvReceptionSnapshot
import com.andreassamitsch.ilauncher.data.livetv.LiveTvSatFailureReason
import com.andreassamitsch.ilauncher.data.livetv.LiveTvSatHealthPolicy
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifResolvedStream
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifStreamHttpException
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.TouchCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import com.andreassamitsch.ilauncher.ui.epg.EpgScreen
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

private const val PLAYER_OVERLAY_TIMEOUT_MILLIS = 3_000L
private const val LONG_OK_THRESHOLD_MILLIS = 650L
private const val SAT_BUFFERING_FALLBACK_MILLIS = 8_000L
private const val MIN_PLAYBACK_FALLBACK_SESSION_MILLIS = 5_000L
private const val RECEPTION_POLL_INTERVAL_MILLIS = 1_000L
private const val LIVE_TV_PLAYER_TAG = "LIVE_TV_PLAYER"
private val LIVE_TV_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

private enum class LiveTvPlaybackSource {
    SATELLITE,
    JOYN,
}

private enum class LiveTvManualSourceOverride {
    NONE,
    SATELLITE,
    JOYN,
}

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
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val previousKeepScreenOn = hostView.keepScreenOn
        hostView.keepScreenOn = true
        onDispose {
            hostView.keepScreenOn = previousKeepScreenOn
        }
    }

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
    var showProgramInfo by remember { mutableStateOf(false) }
    var playbackRestartToken by remember { mutableStateOf(0) }
    var preparedServiceReference by remember { mutableStateOf<String?>(null) }
    var autoRetryAttempt by remember(currentServiceReference) { mutableStateOf(0) }
    var retryingPlayback by remember(currentServiceReference) { mutableStateOf(false) }
    var playbackSource by remember(currentServiceReference) { mutableStateOf(LiveTvPlaybackSource.SATELLITE) }
    var manualSourceOverride by remember(currentServiceReference) { mutableStateOf(LiveTvManualSourceOverride.NONE) }
    var fallbackReason by remember(currentServiceReference) { mutableStateOf<LiveTvSatFailureReason?>(null) }
    var satFailureDetail by remember(currentServiceReference) { mutableStateOf<String?>(null) }
    var activeJoynCountry by remember(currentServiceReference) { mutableStateOf<String?>(null) }
    var activeJoynTitle by remember(currentServiceReference) { mutableStateOf<String?>(null) }
    var receptionSnapshot by remember(currentServiceReference) { mutableStateOf<LiveTvReceptionSnapshot?>(null) }
    var isBuffering by remember(currentServiceReference) { mutableStateOf(false) }
    var joynPrepared by remember(currentServiceReference) { mutableStateOf(false) }
    var showEpg by remember(initialShowEpg, initialServiceReference) { mutableStateOf(initialShowEpg) }
    var selectedEpgServiceReference by remember(initialServiceReference) { mutableStateOf(initialServiceReference) }
    var selectedEpgProgramStartUtcMillis by remember(initialServiceReference, initialEpgProgramStartUtcMillis) {
        mutableStateOf(initialEpgProgramStartUtcMillis)
    }
    val channelSessionStartedAtEpochMillis = remember(currentServiceReference) { System.currentTimeMillis() }

    val joynFallbackRepository = remember(context.applicationContext) {
        JoynLiveTvFallbackRepository(context.applicationContext)
    }
    val fallbackStore = remember(context.applicationContext) {
        LiveTvJoynFallbackStore(context.applicationContext)
    }
    var autoFallbackEnabled by remember(fallbackStore) {
        mutableStateOf(fallbackStore.isAutoFallbackEnabled())
    }
    val receptionMonitor = remember(context.applicationContext) {
        LiveTvReceptionMonitor(context.applicationContext)
    }
    var joynMappings by remember { mutableStateOf<Map<String, JoynBridgeChannel>>(emptyMap()) }
    var joynBridgeError by remember { mutableStateOf<String?>(null) }

    val bouquetMappingKey = remember(channels) {
        channels.joinToString("|") { "${it.serviceReference}\u0000${it.name}" }
    }
    LaunchedEffect(bouquetMappingKey, joynFallbackRepository) {
        runCatching { joynFallbackRepository.primeBouquet(channels) }
            .onSuccess { snapshot ->
                joynMappings = snapshot.mappedByServiceReference
                joynBridgeError = null
                Log.i(
                    LIVE_TV_PLAYER_TAG,
                    "Joyn fallback mapped ${snapshot.mappedCount}/${snapshot.bouquetSize} bouquet channels",
                )
            }
            .onFailure { error ->
                joynBridgeError = error.message ?: error.javaClass.simpleName
                Log.w(LIVE_TV_PLAYER_TAG, "Joyn fallback inventory unavailable", error)
            }
    }

    DisposableEffect(joynFallbackRepository) {
        onDispose { joynFallbackRepository.close() }
    }

    val epgChannelListState = rememberLazyListState()
    val epgProgramListState = rememberLazyListState()
    val zapListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val rootFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }
    val epgButtonFocusRequester = remember { FocusRequester() }
    val epgBackFocusRequester = remember { FocusRequester() }
    val programInfoFocusRequester = remember { FocusRequester() }
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
    val currentProgram = currentChannel?.let { channel ->
        val now = System.currentTimeMillis()
        epgState.guide(channel.serviceReference)
            .firstOrNull { now >= it.startUtcMillis && now < it.endUtcMillis }
            ?: channel.now
    }

    fun resetForChannelChange() {
        joynFallbackRepository.releasePlayback()
        channelOverviewPinned = false
        overlayVisible = true
        showExitConfirmation = false
        showProgramInfo = false
        autoRetryAttempt = 0
        retryingPlayback = false
        errorMessage = null
    }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val nextIndex = LiveTvZapping.nextIndex(currentIndex, channels.size, delta)
        resetForChannelChange()
        currentServiceReference = channels[nextIndex].serviceReference
    }

    fun selectChannel(index: Int) {
        if (index !in channels.indices) return
        resetForChannelChange()
        currentServiceReference = channels[index].serviceReference
    }

    fun autoFallbackAllowed(): Boolean =
        autoFallbackEnabled &&
            manualSourceOverride == LiveTvManualSourceOverride.NONE &&
            joynMappings.containsKey(currentServiceReference)

    fun requestJoynFallback(reason: LiveTvSatFailureReason, detail: String? = null): Boolean {
        if (playbackSource != LiveTvPlaybackSource.SATELLITE || !autoFallbackAllowed()) return false
        fallbackReason = reason
        satFailureDetail = detail
        autoRetryAttempt = 0
        retryingPlayback = false
        loading = true
        isBuffering = false
        errorMessage = null
        overlayVisible = true
        playbackSource = LiveTvPlaybackSource.JOYN
        Log.w(
            LIVE_TV_PLAYER_TAG,
            "SAT -> Joyn fallback for $currentServiceReference: ${reason.name}" +
                detail?.let { " · $it" }.orEmpty(),
        )
        return true
    }

    fun surfaceSatFailure(reason: LiveTvSatFailureReason, detail: String? = null) {
        loading = false
        isBuffering = false
        retryingPlayback = false
        overlayVisible = true
        errorMessage = buildString {
            append(detail?.takeIf(String::isNotBlank) ?: reason.overlayText)
            if (joynMappings.containsKey(currentServiceReference)) {
                append(" · Joyn kann manuell gewählt werden.")
            }
        }
    }

    fun switchSourceManually(target: LiveTvPlaybackSource) {
        val channel = currentChannel ?: return
        if (target == LiveTvPlaybackSource.JOYN && !joynMappings.containsKey(channel.serviceReference)) {
            overlayVisible = true
            errorMessage = "Für ${channel.name} ist kein Joyn-Sender im aktuellen Bouquet-Mapping verfügbar."
            return
        }

        manualSourceOverride = when (target) {
            LiveTvPlaybackSource.SATELLITE -> LiveTvManualSourceOverride.SATELLITE
            LiveTvPlaybackSource.JOYN -> LiveTvManualSourceOverride.JOYN
        }
        fallbackReason = null
        satFailureDetail = null
        autoRetryAttempt = 0
        retryingPlayback = false
        loading = true
        isBuffering = false
        errorMessage = null
        overlayVisible = true

        if (playbackSource == target) {
            playbackRestartToken += 1
        } else {
            playbackSource = target
        }
    }

    fun toggleAutoFallback() {
        autoFallbackEnabled = !autoFallbackEnabled
        fallbackStore.setAutoFallbackEnabled(autoFallbackEnabled)
        overlayVisible = true
    }

    fun openChannelOverview() {
        showExitConfirmation = false
        showProgramInfo = false
        overlayVisible = true
        channelOverviewPinned = true
    }

    fun openProgramInfo() {
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
        showProgramInfo = false
        channelOverviewPinned = false
        overlayVisible = true
        showExitConfirmation = true
    }

    BackHandler {
        when {
            showExitConfirmation -> showExitConfirmation = false
            showProgramInfo -> {
                showProgramInfo = false
                openChannelOverview()
            }
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
            joynFallbackRepository.releasePlayback()
            currentServiceReference = channels.first().serviceReference
        }
    }

    // Prepare the exact current Joyn channel while SAT is still playing. The repository keeps the
    // resolved manifest/DRM/loopback bridge for this channel session so the actual switch is fast.
    LaunchedEffect(currentChannel?.serviceReference, joynMappings, joynFallbackRepository) {
        val channel = currentChannel ?: return@LaunchedEffect
        if (!joynMappings.containsKey(channel.serviceReference)) {
            joynPrepared = false
            return@LaunchedEffect
        }
        joynPrepared = false
        runCatching { joynFallbackRepository.prewarm(channel) }
            .onSuccess { playback ->
                if (playback != null && channel.serviceReference == currentServiceReference) {
                    joynPrepared = true
                    Log.i(
                        LIVE_TV_PLAYER_TAG,
                        "Joyn fallback prewarmed for ${channel.serviceReference} (${playback.country})",
                    )
                }
            }
            .onFailure { error ->
                Log.w(LIVE_TV_PLAYER_TAG, "Joyn fallback prewarm failed for ${channel.name}", error)
            }
    }

    // Keep reception and OSCam health alive even after the transient overlay disappears.
    LaunchedEffect(currentChannel?.serviceReference, playbackSource, receptionMonitor) {
        val channel = currentChannel ?: return@LaunchedEffect
        receptionSnapshot = null
        if (playbackSource != LiveTvPlaybackSource.SATELLITE) return@LaunchedEffect

        val healthPolicy = LiveTvSatHealthPolicy()
        while (true) {
            val snapshot = runCatching { receptionMonitor.sample(channel) }.getOrNull()
            if (snapshot != null) {
                receptionSnapshot = snapshot
                val failure = healthPolicy.update(snapshot)
                if (failure != null && requestJoynFallback(failure)) {
                    break
                }
            }
            delay(RECEPTION_POLL_INTERVAL_MILLIS)
        }
    }

    // SAT stays preferred. Only sustained buffering causes an automatic source change, and even
    // that waits longer than before. Turning automation off or manually selecting SAT suppresses it.
    LaunchedEffect(
        isBuffering,
        playbackSource,
        currentServiceReference,
        preparedServiceReference,
        retryingPlayback,
        autoFallbackEnabled,
        manualSourceOverride,
    ) {
        if (
            !isBuffering ||
            playbackSource != LiveTvPlaybackSource.SATELLITE ||
            preparedServiceReference != currentServiceReference ||
            retryingPlayback ||
            !autoFallbackAllowed()
        ) {
            return@LaunchedEffect
        }
        delay(SAT_BUFFERING_FALLBACK_MILLIS)
        if (
            isBuffering &&
            playbackSource == LiveTvPlaybackSource.SATELLITE &&
            preparedServiceReference == currentServiceReference &&
            !retryingPlayback &&
            autoFallbackAllowed()
        ) {
            requestJoynFallback(LiveTvSatFailureReason.BUFFERING)
        }
    }

    DisposableEffect(player, playbackSource, currentServiceReference, autoFallbackEnabled, manualSourceOverride) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                loading = playbackState == Player.STATE_BUFFERING ||
                    (playbackState == Player.STATE_IDLE && errorMessage == null)
                if (playbackState == Player.STATE_READY) {
                    errorMessage = null
                    retryingPlayback = false
                    autoRetryAttempt = 0
                    isBuffering = false
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

                if (playbackSource == LiveTvPlaybackSource.SATELLITE) {
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

                    val channelAge = System.currentTimeMillis() - channelSessionStartedAtEpochMillis
                    if (
                        error.errorCode in setOf(
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        ) &&
                        channelAge < MIN_PLAYBACK_FALLBACK_SESSION_MILLIS
                    ) {
                        retryingPlayback = true
                        loading = true
                        overlayVisible = true
                        errorMessage = null
                        playbackRestartToken += 1
                        Log.w(
                            LIVE_TV_PLAYER_TAG,
                            "Keeping SAT during startup grace after ${error.errorCodeName} (${channelAge}ms)",
                        )
                        return
                    }

                    if (
                        !requestJoynFallback(
                            reason = LiveTvSatFailureReason.PLAYBACK,
                            detail = error.errorCodeName,
                        )
                    ) {
                        surfaceSatFailure(
                            LiveTvSatFailureReason.PLAYBACK,
                            "SAT-Wiedergabe fehlgeschlagen (${error.errorCodeName}).",
                        )
                    }
                    return
                }

                retryingPlayback = false
                loading = false
                isBuffering = false
                overlayVisible = true
                errorMessage = "Joyn-Wiedergabe fehlgeschlagen (${joynPlaybackErrorMessage(error)})."
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(currentChannel?.serviceReference, playbackRestartToken, playbackSource) {
        val channel = currentChannel ?: return@LaunchedEffect
        val sourceForStart = playbackSource
        val retryAttemptForStart = autoRetryAttempt
        if (!showEpg) {
            selectedEpgServiceReference = channel.serviceReference
            selectedEpgProgramStartUtcMillis = null
        }
        loading = true
        retryingPlayback = sourceForStart == LiveTvPlaybackSource.SATELLITE && retryAttemptForStart > 0
        errorMessage = null
        preparedServiceReference = null
        isBuffering = false
        player.stop()
        player.clearMediaItems()

        if (sourceForStart == LiveTvPlaybackSource.SATELLITE && retryAttemptForStart > 0) {
            delay(LiveTvPlaybackRecovery.retryDelayMillis(retryAttemptForStart))
        }

        try {
            val mediaSource = when (sourceForStart) {
                LiveTvPlaybackSource.SATELLITE -> {
                    val stream = onResolveStream(channel)
                    buildSatMediaSource(stream, channel)
                }

                LiveTvPlaybackSource.JOYN -> {
                    val fallback = joynFallbackRepository.resolve(channel)
                        ?: error("Für ${channel.name} ist im gewählten Bouquet kein passender Joyn-Sender verfügbar.")
                    activeJoynCountry = fallback.country
                    activeJoynTitle = fallback.channelTitle
                    joynPrepared = true
                    buildJoynMediaSource(fallback, channel)
                }
            }

            preparedServiceReference = channel.serviceReference
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            preparedServiceReference = null
            isBuffering = false
            retryingPlayback = false
            overlayVisible = true

            if (sourceForStart == LiveTvPlaybackSource.SATELLITE) {
                val reason = when (throwable) {
                    is UnknownHostException,
                    is ConnectException,
                    is SocketTimeoutException,
                    -> LiveTvSatFailureReason.RECEIVER
                    else -> LiveTvSatFailureReason.PLAYBACK
                }
                val detail = playbackErrorMessage(throwable)
                if (!requestJoynFallback(reason, detail)) {
                    surfaceSatFailure(reason, detail)
                }
            } else {
                loading = false
                val bridgeDetail = throwable.message ?: throwable.javaClass.simpleName
                errorMessage = buildString {
                    if (manualSourceOverride == LiveTvManualSourceOverride.JOYN) {
                        append("Joyn konnte nicht gestartet werden: ")
                    } else {
                        append(satFailureDetail ?: fallbackReason?.overlayText ?: "SAT-Fallback")
                        append(" · Joyn konnte nicht übernehmen: ")
                    }
                    append(bridgeDetail.take(300))
                    joynBridgeError?.takeIf { it.isNotBlank() && it != bridgeDetail }?.let {
                        append(" · Bridge: ")
                        append(it.take(180))
                    }
                }
            }
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
        showProgramInfo,
        showExitConfirmation,
    ) {
        if (
            overlayVisible && !channelOverviewPinned && !loading && errorMessage == null &&
            !showEpg && !showProgramInfo && !showExitConfirmation
        ) {
            delay(PLAYER_OVERLAY_TIMEOUT_MILLIS)
            overlayVisible = false
        }
    }

    LaunchedEffect(
        overlayVisible,
        channelOverviewPinned,
        showEpg,
        showProgramInfo,
        showExitConfirmation,
        currentIndex,
        selectedEpgProgramStartUtcMillis,
    ) {
        withFrameNanos { }
        when {
            showExitConfirmation -> runCatching { exitConfirmFocusRequester.requestFocus() }
            showProgramInfo -> runCatching { programInfoFocusRequester.requestFocus() }
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
                if (showEpg || showProgramInfo || showExitConfirmation) return@onPreviewKeyEvent false
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
                    keepScreenOn = true
                    this.player = player
                    isClickable = true
                    setOnClickListener {
                        if (!showEpg && !showProgramInfo && !showExitConfirmation) openChannelOverview()
                    }
                }
            },
            update = {
                it.keepScreenOn = true
                it.player = player
                it.setOnClickListener {
                    if (!showEpg && !showExitConfirmation) openChannelOverview()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayVisible && !showEpg) {
            currentChannel?.let { channel ->
                val mappedJoyn = joynMappings[channel.serviceReference]
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

                        when (playbackSource) {
                            LiveTvPlaybackSource.SATELLITE -> {
                                Text(
                                    buildString {
                                        append("Quelle · SAT")
                                        mappedJoyn?.let { mapped ->
                                            append(" · Joyn ${mapped.country}")
                                            append(if (joynPrepared) " bereit" else " wird vorbereitet")
                                        }
                                        when {
                                            manualSourceOverride == LiveTvManualSourceOverride.SATELLITE -> append(" · SAT manuell")
                                            autoFallbackEnabled -> append(" · Auto")
                                            else -> append(" · Auto aus")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LiveTvSignalDiagnostics(receptionSnapshot)
                            }

                            LiveTvPlaybackSource.JOYN -> {
                                Text(
                                    buildString {
                                        append("Quelle · Joyn")
                                        activeJoynCountry?.takeIf(String::isNotBlank)?.let { append(" $it") }
                                        if (manualSourceOverride == LiveTvManualSourceOverride.JOYN) {
                                            append(" · manuell")
                                        } else {
                                            append(" · Fallback")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (manualSourceOverride != LiveTvManualSourceOverride.JOYN) {
                                    val reason = fallbackReason?.overlayText ?: satFailureDetail
                                    if (!reason.isNullOrBlank()) {
                                        Text(
                                            "Satellit gestört · $reason",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                activeJoynTitle
                                    ?.takeIf { it.isNotBlank() && !it.equals(channel.name, ignoreCase = true) }
                                    ?.let {
                                        Text(
                                            "Joyn-Sender · $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                            }
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
                                when {
                                    playbackSource == LiveTvPlaybackSource.JOYN &&
                                        manualSourceOverride == LiveTvManualSourceOverride.JOYN -> "Joyn wird gestartet …"
                                    playbackSource == LiveTvPlaybackSource.JOYN -> "Joyn-Fallback wird gestartet …"
                                    retryingPlayback -> "SAT-Stream wird erneut gestartet …"
                                    else -> "Live TV wird geladen …"
                                },
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
                        TouchButton(onClick = ::openProgramInfo) {
                            Text("Info")
                        }
                        TouchButton(onClick = ::toggleAutoFallback) {
                            Text(if (autoFallbackEnabled) "Auto-Fallback: EIN" else "Auto-Fallback: AUS")
                        }
                        val mappedJoyn = currentChannel?.let { joynMappings[it.serviceReference] }
                        if (mappedJoyn != null || playbackSource == LiveTvPlaybackSource.JOYN) {
                            TouchButton(
                                onClick = {
                                    switchSourceManually(
                                        if (playbackSource == LiveTvPlaybackSource.SATELLITE) {
                                            LiveTvPlaybackSource.JOYN
                                        } else {
                                            LiveTvPlaybackSource.SATELLITE
                                        },
                                    )
                                },
                            ) {
                                Text(
                                    if (playbackSource == LiveTvPlaybackSource.SATELLITE) {
                                        "Zu Joyn ${mappedJoyn?.country.orEmpty()}".trim()
                                    } else {
                                        "Zu SAT"
                                    },
                                )
                            }
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

        if (showProgramInfo) {
            val program = currentProgram
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(760.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), RoundedCornerShape(22.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                            RoundedCornerShape(22.dp),
                        )
                        .padding(horizontal = 30.dp, vertical = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        currentChannel?.name ?: "Live TV",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (program != null) {
                        Text(program.title, style = MaterialTheme.typography.headlineSmall)
                        program.subtitle?.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${formatLiveTvStartTime(program.startUtcMillis)}–${formatLiveTvStartTime(program.endUtcMillis)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val details = buildList {
                            program.seasonNumber?.let { add("Staffel $it") }
                            program.episodeNumber?.let { add("Folge $it") }
                            program.releaseYear?.let { add(it.toString()) }
                            program.categories.orEmpty().filter(String::isNotBlank).take(3).forEach(::add)
                        }.distinct()
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            program.longDescription
                                ?.takeIf(String::isNotBlank)
                                ?: program.shortDescription?.takeIf(String::isNotBlank)
                                ?: "Für diese Sendung ist keine Beschreibung verfügbar.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 9,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            "Für die aktuelle Sendung sind keine Programminformationen verfügbar.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TouchButton(
                            onClick = {
                                showProgramInfo = false
                                openChannelOverview()
                            },
                            modifier = Modifier.focusRequester(programInfoFocusRequester),
                        ) {
                            Text("Zurück")
                        }
                        TouchButton(
                            onClick = {
                                val channel = currentChannel
                                val selected = currentProgram
                                if (channel != null && selected != null) {
                                    onOpenEpgProgramDetails(channel, selected)
                                }
                            },
                            enabled = program != null,
                        ) {
                            Text("Details")
                        }
                    }
                }
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

@OptIn(UnstableApi::class)
private fun buildSatMediaSource(
    stream: OpenWebifResolvedStream,
    channel: LiveTvChannel,
): MediaSource {
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
    return if (stream.isHls) {
        HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    } else {
        ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    }
}

@OptIn(UnstableApi::class)
private fun buildJoynMediaSource(
    playback: JoynFallbackPlayback,
    channel: LiveTvChannel,
): MediaSource {
    // Do not resolve even the loopback bridge host while Compose's LaunchedEffect is on the main
    // thread. OkHttp resolves this unresolved proxy address later on its own worker thread.
    val proxy = Proxy(
        Proxy.Type.HTTP,
        InetSocketAddress.createUnresolved(playback.proxyHost, playback.proxyPort),
    )
    val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    val dataSourceFactory = OkHttpDataSource.Factory(client)
        .setUserAgent(JOYN_USER_AGENT)

    val drmSessionManager = playback.licenseUrl?.let { licenseUrl ->
        val callback = HttpMediaDrmCallback(licenseUrl, dataSourceFactory).apply {
            setKeyRequestProperty("User-Agent", JOYN_USER_AGENT)
            setKeyRequestProperty("Content-Type", "application/octet-stream")
        }
        DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(callback)
    }

    val mediaItem = MediaItem.Builder()
        .setUri(playback.manifestUrl)
        .setMediaId(channel.serviceReference)
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(channel.name).build())
        .setMimeType(MimeTypes.APPLICATION_MPD)
        .build()

    val factory = DashMediaSource.Factory(dataSourceFactory)
    if (drmSessionManager != null) {
        factory.setDrmSessionManagerProvider { drmSessionManager }
    }
    return factory.createMediaSource(mediaItem)
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

private fun joynPlaybackErrorMessage(error: PlaybackException): String {
    var current: Throwable? = error
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return "${error.errorCodeName}: HTTP ${current.responseCode}"
        }
        current = current.cause
    }
    return error.errorCodeName
}

private const val JOYN_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
