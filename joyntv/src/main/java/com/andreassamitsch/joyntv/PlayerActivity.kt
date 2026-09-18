package com.andreassamitsch.joyntv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val continueStore = JoynContinueWatchingStore(applicationContext)
        val watchNextAssetId = intent.data
            ?.takeIf { it.scheme == "ilauncherjoyn" && it.host == "watchnext" }
            ?.lastPathSegment
            ?.takeIf(String::isNotBlank)
        val explicitResumeAssetId = intent.getStringExtra(EXTRA_RESUME_ASSET_ID)
        val explicitResumePositionMs = intent.getLongExtra(EXTRA_RESUME_POSITION_MS, -1L)
            .takeIf { it > 0L }
        val storedContinue = continueStore.find(watchNextAssetId ?: explicitResumeAssetId)
        val resumeMedia = storedContinue?.media
        val resumePlaybackRef = resumeMedia?.videoId
            ?: resumeMedia?.path?.takeIf { resumeMedia.type == JoynMediaType.MOVIE && it.isNotBlank() }
        val contentId = resumePlaybackRef
            ?: intent.getStringExtra(EXTRA_CONTENT_ID)
            ?: intent.getStringExtra(EXTRA_CHANNEL_ID)
            ?: watchNextAssetId
            ?: intent.data?.lastPathSegment
            ?: run {
                finish()
                return
            }
        val title = resumeMedia?.title
            ?: intent.getStringExtra(EXTRA_TITLE)
            ?: intent.getStringExtra(EXTRA_CHANNEL_TITLE)
            ?: "Joyn"
        val streamType = if (watchNextAssetId != null || explicitResumeAssetId != null) STREAM_VOD
            else intent.getStringExtra(EXTRA_STREAM_TYPE) ?: STREAM_LIVE
        val media = resumeMedia ?: mediaFromIntent(intent, contentId, title, streamType)
        val resumeAssetId = explicitResumeAssetId ?: storedContinue?.assetId
        val resumePositionMs = explicitResumePositionMs ?: storedContinue?.positionMs
        val repository = JoynRepository(applicationContext)

        setContent {
            JoynTvTheme {
                JoynPlayer(
                    repository = repository,
                    contentId = contentId,
                    title = title,
                    streamType = streamType,
                    media = media,
                    resumeAssetId = resumeAssetId,
                    requestedStartPositionMs = resumePositionMs,
                )
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "joyn_channel_id"
        const val EXTRA_CHANNEL_TITLE = "joyn_channel_title"
        private const val EXTRA_CONTENT_ID = "joyn_content_id"
        private const val EXTRA_TITLE = "joyn_title"
        private const val EXTRA_STREAM_TYPE = "joyn_stream_type"
        private const val EXTRA_MEDIA_ID = "joyn_media_id"
        private const val EXTRA_MEDIA_TYPE = "joyn_media_type"
        private const val EXTRA_MEDIA_PATH = "joyn_media_path"
        private const val EXTRA_MEDIA_DESCRIPTION = "joyn_media_description"
        private const val EXTRA_MEDIA_IMAGE = "joyn_media_image"
        private const val EXTRA_MEDIA_BACKDROP = "joyn_media_backdrop"
        private const val EXTRA_MEDIA_LOGO = "joyn_media_logo"
        private const val EXTRA_MEDIA_VIDEO_ID = "joyn_media_video_id"
        private const val EXTRA_MEDIA_SEASON_ID = "joyn_media_season_id"
        private const val EXTRA_MEDIA_SERIES_TITLE = "joyn_media_series_title"
        private const val EXTRA_MEDIA_SERIES_ID = "joyn_media_series_id"
        private const val EXTRA_MEDIA_SERIES_PATH = "joyn_media_series_path"
        private const val EXTRA_MEDIA_SEASON_NUMBER = "joyn_media_season_number"
        private const val EXTRA_MEDIA_EPISODE_NUMBER = "joyn_media_episode_number"
        private const val EXTRA_RESUME_ASSET_ID = "joyn_resume_asset_id"
        private const val EXTRA_RESUME_POSITION_MS = "joyn_resume_position_ms"
        private const val STREAM_LIVE = "LIVE"
        private const val STREAM_VOD = "VOD"

        fun intent(context: Context, channelId: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CONTENT_ID, channelId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STREAM_TYPE, STREAM_LIVE)
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_CHANNEL_TITLE, title)
            }

        fun vodIntent(context: Context, item: JoynMediaItem): Intent {
            val contentRef = item.videoId
                ?: item.path?.takeIf { item.type == JoynMediaType.MOVIE && it.isNotBlank() }
                ?: item.id
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CONTENT_ID, contentRef)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_STREAM_TYPE, STREAM_VOD)
                putExtra(EXTRA_MEDIA_ID, item.id)
                putExtra(EXTRA_MEDIA_TYPE, item.type.name)
                item.path?.let { putExtra(EXTRA_MEDIA_PATH, it) }
                item.description?.let { putExtra(EXTRA_MEDIA_DESCRIPTION, it) }
                item.imageUrl?.let { putExtra(EXTRA_MEDIA_IMAGE, it) }
                item.backdropUrl?.let { putExtra(EXTRA_MEDIA_BACKDROP, it) }
                item.logoUrl?.let { putExtra(EXTRA_MEDIA_LOGO, it) }
                item.videoId?.let { putExtra(EXTRA_MEDIA_VIDEO_ID, it) }
                item.seasonId?.let { putExtra(EXTRA_MEDIA_SEASON_ID, it) }
                item.seriesTitle?.let { putExtra(EXTRA_MEDIA_SERIES_TITLE, it) }
                item.seriesId?.let { putExtra(EXTRA_MEDIA_SERIES_ID, it) }
                item.seriesPath?.let { putExtra(EXTRA_MEDIA_SERIES_PATH, it) }
                item.seasonNumber?.let { putExtra(EXTRA_MEDIA_SEASON_NUMBER, it) }
                item.episodeNumber?.let { putExtra(EXTRA_MEDIA_EPISODE_NUMBER, it) }
            }
        }

        internal fun vodResumeIntent(context: Context, entry: JoynContinueWatchingEntry): Intent =
            vodIntent(context, entry.media).apply {
                putExtra(EXTRA_RESUME_ASSET_ID, entry.assetId)
                putExtra(EXTRA_RESUME_POSITION_MS, entry.positionMs)
            }

        fun vodIntent(context: Context, videoId: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CONTENT_ID, videoId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STREAM_TYPE, STREAM_VOD)
            }
    }
}

private fun mediaFromIntent(
    intent: Intent,
    contentId: String,
    title: String,
    streamType: String,
): JoynMediaItem? {
    if (streamType != "VOD") return null
    val type = intent.getStringExtra("joyn_media_type")
        ?.let { runCatching { JoynMediaType.valueOf(it) }.getOrNull() }
        ?: return null
    return JoynMediaItem(
        id = intent.getStringExtra("joyn_media_id") ?: contentId,
        title = title,
        description = intent.getStringExtra("joyn_media_description"),
        path = intent.getStringExtra("joyn_media_path"),
        type = type,
        imageUrl = intent.getStringExtra("joyn_media_image"),
        backdropUrl = intent.getStringExtra("joyn_media_backdrop"),
        logoUrl = intent.getStringExtra("joyn_media_logo"),
        videoId = intent.getStringExtra("joyn_media_video_id"),
        seasonId = intent.getStringExtra("joyn_media_season_id"),
        seriesTitle = intent.getStringExtra("joyn_media_series_title"),
        seasonNumber = intent.getIntExtra("joyn_media_season_number", -1).takeIf { it > 0 },
        episodeNumber = intent.getIntExtra("joyn_media_episode_number", -1).takeIf { it > 0 },
        seriesId = intent.getStringExtra("joyn_media_series_id"),
        seriesPath = intent.getStringExtra("joyn_media_series_path"),
    )
}

@Composable
private fun JoynPlayer(
    repository: JoynRepository,
    contentId: String,
    title: String,
    streamType: String,
    media: JoynMediaItem?,
    resumeAssetId: String?,
    requestedStartPositionMs: Long?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val streamProxyFallback = remember(context.applicationContext) {
        JoynStreamProxyFallback(context.applicationContext)
    }
    val continueStore = remember(context.applicationContext) {
        JoynContinueWatchingStore(context.applicationContext)
    }
    val watchNextPublisher = remember(context.applicationContext) {
        JoynWatchNextPublisher(context.applicationContext)
    }
    val nextEpisodeStore = remember(context.applicationContext) {
        JoynNextEpisodeStore(context.applicationContext)
    }
    var completionHandled by remember(contentId, streamType, resumeAssetId) { mutableStateOf(false) }
    var nextSuggestionCleared by remember(contentId, streamType, resumeAssetId) { mutableStateOf(false) }
    var startPositionMs by remember(contentId, streamType, resumeAssetId) { mutableStateOf(0L) }
    var lastRemoteSyncAt by remember(contentId, streamType, resumeAssetId) { mutableStateOf(0L) }
    var playback by remember(contentId, streamType) { mutableStateOf<JoynPlayback?>(null) }
    var errorText by remember(contentId, streamType) { mutableStateOf<String?>(null) }
    var retryKey by remember(contentId, streamType) { mutableIntStateOf(0) }
    var pinAttemptKey by remember(contentId, streamType) { mutableIntStateOf(0) }
    var pinOverride by remember(contentId, streamType) { mutableStateOf<String?>(null) }
    var pinRequested by remember(contentId, streamType) { mutableStateOf(false) }
    var pinInvalid by remember(contentId, streamType) { mutableStateOf(false) }
    var loginRequired by remember(contentId, streamType) { mutableStateOf(false) }
    var playerRouteKey by remember(contentId, streamType) { mutableIntStateOf(0) }
    var fullProxyActive by remember(contentId, streamType) { mutableStateOf(false) }
    var fallbackAttempted by remember(contentId, streamType) { mutableStateOf(false) }
    var fallbackPendingConfirmation by remember(contentId, streamType) { mutableStateOf(false) }
    var wireGuardFallbackAttempted by remember(contentId, streamType) { mutableStateOf(false) }
    var routeRecoveryInProgress by remember(contentId, streamType) { mutableStateOf(false) }

    DisposableEffect(contentId, streamType) {
        onDispose {
            if (streamType == "LIVE") {
                streamProxyFallback.restoreSavedRouting()
            }
        }
    }

    LaunchedEffect(contentId, streamType, resumeAssetId, requestedStartPositionMs, retryKey, pinAttemptKey) {
        playback = null
        errorText = null
        pinRequested = false
        loginRequired = false
        fallbackAttempted = false
        fallbackPendingConfirmation = false
        wireGuardFallbackAttempted = false
        routeRecoveryInProgress = false
        fullProxyActive = false
        playerRouteKey = 0
        startPositionMs = 0L
        lastRemoteSyncAt = 0L
        completionHandled = false
        nextSuggestionCleared = false
        runCatching {
            withContext(Dispatchers.IO) {
                if (streamType == "LIVE") {
                    // Restore the pinned HTTP-proxy base route when one exists. When MainActivity
                    // prepared an app-scoped WireGuard route, JoynProxySettings keeps an explicit
                    // direct/tunnel runtime marker and this call deliberately leaves that route alone.
                    streamProxyFallback.restoreSavedRouting()
                }
                val resolved = if (streamType == "VOD") {
                    repository.resolveVodPlayback(contentId, pinOverride)
                } else {
                    repository.resolveLivePlayback(contentId)
                }
                val useFullProxy = if (streamType == "LIVE") {
                    // Known geo-blocked channels are promoted only after the playback URL is resolved.
                    streamProxyFallback.prepareLiveChannel(contentId)
                } else {
                    false
                }
                resolved to useFullProxy
            }
        }.onSuccess { (resolved, useFullProxy) ->
            if (streamType == "VOD") {
                val lookupId = resumeAssetId ?: resolved.assetId ?: contentId
                startPositionMs = requestedStartPositionMs
                    ?.takeIf { it > 0L }
                    ?: continueStore.find(lookupId)?.positionMs
                    ?: continueStore.find(resolved.assetId ?: contentId)?.positionMs
                    ?: 0L
            }
            playback = resolved
            fullProxyActive = useFullProxy
            pinInvalid = false
        }.onFailure { error ->
            val message = error.message.orEmpty()
            val requiresPin = error is JoynPinRequiredException ||
                message.contains("ENT_PINRequired", ignoreCase = true)
            val invalidPin = error is JoynPinInvalidException ||
                message.contains("ENT_PINInvalid", ignoreCase = true)
            when {
                error is JoynLoginRequiredException -> loginRequired = true
                streamType == "VOD" && (requiresPin || invalidPin) -> {
                    if (invalidPin && pinOverride == null && repository.parentalPinAutoUse()) {
                        repository.setParentalPinAutoUse(false)
                    }
                    pinInvalid = invalidPin
                    pinRequested = true
                }
                else -> errorText = error.message ?: error.javaClass.simpleName
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            playback != null -> Media3Player(
                playback = requireNotNull(playback),
                routeKey = playerRouteKey,
                startPositionMs = startPositionMs,
                onProgress = { positionMs, durationMs, ended ->
                    if (streamType == "VOD" && media != null && durationMs > 0L) {
                        val assetId = resumeAssetId ?: playback?.assetId ?: contentId
                        if (
                            media.type == JoynMediaType.EPISODE &&
                            positionMs > 0L &&
                            !nextSuggestionCleared
                        ) {
                            JoynNextEpisodeStore.seriesKey(media)?.let(watchNextPublisher::removeNextEpisode)
                            nextSuggestionCleared = true
                        }
                        val finished = ended || JoynContinueWatchingPolicy.isFinished(positionMs, durationMs)
                        if (finished) {
                            if (!completionHandled) {
                                completionHandled = true
                                continueStore.remove(assetId, pendingRemoteDelete = true)
                                watchNextPublisher.remove(assetId)
                                nextEpisodeStore.markCompleted(media)?.let { completed ->
                                    watchNextPublisher.removeNextEpisode(completed.seriesKey)
                                    JoynNextEpisodeScheduler.enqueueNow(context)
                                }
                                scope.launch {
                                    val cleared = withContext(Dispatchers.IO) {
                                        runCatching { repository.setResumePosition(assetId, 0) }.getOrDefault(false)
                                    }
                                    if (cleared) continueStore.clearPendingDelete(assetId)
                                }
                            }
                        } else {
                            val entry = continueStore.upsert(
                                assetId = assetId,
                                media = media,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                dirty = true,
                            )
                            if (entry != null) {
                                watchNextPublisher.publish(entry)
                                val now = System.currentTimeMillis()
                                if (now - lastRemoteSyncAt >= 30_000L) {
                                    lastRemoteSyncAt = now
                                    scope.launch {
                                        val synced = withContext(Dispatchers.IO) {
                                            runCatching {
                                                repository.setResumePosition(
                                                    assetId,
                                                    (positionMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                                )
                                            }.getOrDefault(false)
                                        }
                                        if (synced) continueStore.markSynced(assetId)
                                    }
                                }
                            }
                        }
                    }
                },
                onPlaybackReady = {
                    if (streamType == "LIVE" && fallbackPendingConfirmation) {
                        streamProxyFallback.confirmFullProxyRequired(contentId)
                        fallbackPendingConfirmation = false
                    }
                },
                onPlaybackError = { error ->
                    if (!routeRecoveryInProgress) {
                        val httpStatus = playbackHttpStatus(error)
                        val badHttpStatus = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                        val canTryFullProxy =
                            streamType == "LIVE" &&
                                !fullProxyActive &&
                                !fallbackAttempted &&
                                badHttpStatus
                        val canTryWireGuard =
                            streamType == "LIVE" &&
                                !wireGuardFallbackAttempted &&
                                badHttpStatus &&
                                httpStatus == 403 &&
                                (fullProxyActive || fallbackAttempted)

                        when {
                            canTryFullProxy && streamProxyFallback.tryTemporaryFullProxy() -> {
                                // Keep the already resolved Joyn playback data. Only restart Media3 so
                                // manifest/DRM/segments open through the same residential proxy lease.
                                fallbackAttempted = true
                                fallbackPendingConfirmation = true
                                fullProxyActive = true
                                playerRouteKey++
                            }

                            canTryWireGuard -> {
                                // Some live CDNs (notably the SRF/P7S1 path) can accept Joyn's
                                // entitlement through the residential HTTP proxy and still reject the
                                // manifest itself with HTTP 403. Retry the exact manifest once through
                                // an app-scoped stable Mysterium WireGuard exit for the channel country.
                                wireGuardFallbackAttempted = true
                                routeRecoveryInProgress = true
                                fallbackPendingConfirmation = false
                                scope.launch {
                                    val tunnelResult = withContext(Dispatchers.IO) {
                                        streamProxyFallback.tryWireGuardFallback(contentId)
                                    }
                                    routeRecoveryInProgress = false
                                    if (tunnelResult.isSuccess) {
                                        fullProxyActive = true
                                        playerRouteKey++
                                    } else {
                                        playback = null
                                        fullProxyActive = false
                                        val detail = tunnelResult.exceptionOrNull()?.message
                                            ?: tunnelResult.exceptionOrNull()?.javaClass?.simpleName
                                            ?: "unbekannter Fehler"
                                        errorText = "${playbackErrorMessage(error)} · Tunnel-Fallback: $detail"
                                    }
                                }
                            }

                            else -> {
                                if (streamType == "LIVE") {
                                    streamProxyFallback.restoreSavedRouting()
                                }
                                playback = null
                                fullProxyActive = false
                                fallbackPendingConfirmation = false
                                errorText = playbackErrorMessage(error)
                            }
                        }
                    }
                },
            )
            loginRequired -> PlaybackLoginRequired(
                title = title,
                onLogin = {
                    context.startActivity(Intent(context, LoginActivity::class.java))
                },
                onRetry = {
                    pinOverride = null
                    retryKey++
                },
            )
            pinRequested -> ParentalPinPrompt(
                title = title,
                invalid = pinInvalid,
                onSubmit = { pin ->
                    pinOverride = pin
                    pinRequested = false
                    pinAttemptKey++
                },
            )
            errorText != null -> PlaybackError(
                title = title,
                message = errorText.orEmpty(),
                onRetry = {
                    if (streamType == "LIVE") {
                        streamProxyFallback.restoreSavedRouting()
                    }
                    pinOverride = null
                    retryKey++
                },
            )
            else -> Text(
                text = "$title wird gestartet …",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun PlaybackLoginRequired(
    title: String,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Anmeldung erforderlich",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "„$title“ ist jugendgeschützt. Du bist in Joyn TV derzeit nicht mit deinem Joyn-Konto angemeldet. Melde dich an; anschließend kann der gespeicherte Jugendschutz-PIN automatisch verwendet werden.",
            color = Color(0xFFD7DBE3),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 760.dp),
        )
        Spacer(Modifier.height(24.dp))
        RetryButton(onLogin, label = "Konto & Region öffnen")
        Spacer(Modifier.height(12.dp))
        RetryButton(onRetry)
    }
}

@Composable
private fun ParentalPinPrompt(
    title: String,
    invalid: Boolean,
    onSubmit: (String) -> Unit,
) {
    var pin by remember(invalid) { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (invalid) "Jugendschutz-PIN falsch" else "Jugendschutz-PIN erforderlich",
            color = if (invalid) Color(0xFFFFB4AB) else Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Für „$title“ verlangt Joyn den 4-stelligen PIN deines Kontos.",
            color = Color(0xFFD7DBE3),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        BasicTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(4) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 7.sp,
            ),
            cursorBrush = SolidColor(Color.White),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .fillMaxWidth()
                        .background(Color(0xFF171C24), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF59636F), RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pin.isBlank()) Text("••••", color = Color(0xFF929AA6), fontSize = 22.sp)
                    inner()
                }
            },
            modifier = Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.height(20.dp))
        PinSubmitButton(enabled = pin.length == 4) { onSubmit(pin) }
        Spacer(Modifier.height(12.dp))
        Text(
            "Tipp: Unter Konto & Region kannst du den PIN verschlüsselt speichern und automatisch verwenden lassen.",
            color = Color(0xFF9FA8B5),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PinSubmitButton(enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .background(
                when {
                    !enabled -> Color(0xFF252A32)
                    focused -> Color.White
                    else -> Color(0xFF20252D)
                },
                shape,
            )
            .border(1.dp, if (focused) Color.White else Color(0xFF5A6470), shape)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (enabled && event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onClick()
                    true
                } else false
            }
            .then(if (enabled) Modifier.clickable(onClick = onClick).focusable() else Modifier)
            .padding(horizontal = 26.dp, vertical = 12.dp),
    ) {
        Text(
            "Freigeben",
            color = if (focused && enabled) Color(0xFF11151B) else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PlaybackError(title: String, message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$title konnte nicht gestartet werden",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message.take(700),
            color = Color(0xFFD7DBE3),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        RetryButton(onRetry)
    }
}

@Composable
private fun RetryButton(onRetry: () -> Unit, label: String = "Erneut versuchen") {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .background(if (focused) Color.White else Color(0xFF20252D), shape)
            .border(1.dp, if (focused) Color.White else Color(0xFF5A6470), shape)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onRetry()
                    true
                } else false
            }
            .clickable(onClick = onRetry)
            .focusable()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = if (focused) Color(0xFF11151B) else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3Player(
    playback: JoynPlayback,
    routeKey: Int,
    startPositionMs: Long,
    onProgress: (positionMs: Long, durationMs: Long, ended: Boolean) -> Unit,
    onPlaybackReady: () -> Unit,
    onPlaybackError: (PlaybackException) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(playback, routeKey) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                val drm = playback.licenseUrl?.let { licenseUrl ->
                    MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(licenseUrl)
                        .setLicenseRequestHeaders(
                            mapOf(
                                "User-Agent" to USER_AGENT,
                                "Content-Type" to "application/octet-stream",
                            ),
                        )
                        .build()
                }
                val media = MediaItem.Builder()
                    .setUri(playback.manifestUrl)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .apply { if (drm != null) setDrmConfiguration(drm) }
                    .build()
                setMediaItem(media)
                if (startPositionMs > 0L) seekTo(startPositionMs)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) onPlaybackReady()
                if (playbackState == Player.STATE_ENDED) {
                    val durationMs = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
                    onProgress(player.currentPosition, durationMs, true)
                }
            }

            override fun onPlayerError(error: PlaybackException) = onPlaybackError(error)
        }
        player.addListener(listener)
        onDispose {
            val durationMs = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
            if (durationMs > 0L) {
                onProgress(
                    player.currentPosition,
                    durationMs,
                    player.playbackState == Player.STATE_ENDED,
                )
            }
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(10_000L)
            val durationMs = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: continue
            onProgress(
                player.currentPosition,
                durationMs,
                player.playbackState == Player.STATE_ENDED,
            )
        }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                controllerAutoShow = true
                controllerHideOnTouch = false
                installJoynQualitySelector(player)
                requestFocus()
            }
        },
        update = { view -> view.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun playbackHttpStatus(error: PlaybackException): Int? {
    var current: Throwable? = error
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
        current = current.cause
    }
    return null
}

private fun playbackErrorMessage(error: PlaybackException): String {
    var current: Throwable? = error
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) {
            val uri = current.dataSpec.uri.toString().take(420)
            return "${error.errorCodeName}: HTTP ${current.responseCode} · $uri"
        }
        current = current.cause
    }
    return "${error.errorCodeName}: ${error.message.orEmpty()}".trim()
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
