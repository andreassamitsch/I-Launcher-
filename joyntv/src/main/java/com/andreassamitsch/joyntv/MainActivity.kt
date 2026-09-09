package com.andreassamitsch.joyntv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        val updateManager = JoynUpdateManager(applicationContext)
        setContent {
            JoynTvTheme {
                JoynHome(
                    repository = repository,
                    updateManager = updateManager,
                    onPlay = { channel ->
                        startActivity(PlayerActivity.intent(this, channel.id, channel.title))
                    },
                )
            }
        }
    }
}

@Composable
private fun JoynHome(
    repository: JoynRepository,
    updateManager: JoynUpdateManager,
    onPlay: (JoynLiveChannel) -> Unit,
) {
    var channels by remember { mutableStateOf<List<JoynLiveChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }
    val updateState by updateManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        updateManager.checkForUpdates()
    }

    LaunchedEffect(updateState) {
        while (updateState is JoynUpdateState.Downloading) {
            delay(700)
            updateManager.refreshDownloadState()
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        errorText = null
        runCatching {
            withContext(Dispatchers.IO) { repository.loadLiveChannelsAndPublish() }
        }.onSuccess {
            channels = it
            selectedIndex = 0
        }.onFailure {
            errorText = it.message ?: it.javaClass.simpleName
        }
        loading = false
    }

    LaunchedEffect(channels) {
        if (channels.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    val selected = channels.getOrNull(selectedIndex)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val compact = maxHeight < 520.dp
        val pageScroll = rememberScrollState()

        selected?.let { channel ->
            AsyncImage(
                model = channel.currentProgram?.imageUrl ?: channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.38f,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x66080A0E),
                        0.52f to Color(0xD9080A0E),
                        1f to Color(0xFF080A0E),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(pageScroll)
                .padding(top = if (compact) 28.dp else 54.dp),
        ) {
            Hero(selected, compact)
            Spacer(Modifier.height(if (compact) 34.dp else 150.dp))

            when {
                loading -> StatusText("Joyn wird geladen …", compact)
                errorText != null -> Column(
                    Modifier.padding(
                        horizontal = if (compact) 28.dp else 64.dp,
                        vertical = 24.dp,
                    ),
                ) {
                    Text(
                        "Joyn konnte nicht geladen werden",
                        color = Color.White,
                        fontSize = if (compact) 20.sp else 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorText.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                    )
                }
                channels.isEmpty() -> StatusText("Keine freien Live-Sender verfügbar.", compact)
                else -> {
                    val horizontalPadding = if (compact) 28.dp else 64.dp
                    Text(
                        text = "Live TV",
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                        color = Color.White,
                        fontSize = if (compact) 18.sp else 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(
                            horizontal = horizontalPadding,
                            vertical = 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
                    ) {
                        itemsIndexed(channels, key = { _, item -> item.id }) { index, channel ->
                            LiveChannelCard(
                                channel = channel,
                                compact = compact,
                                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                onFocused = { selectedIndex = index },
                                onClick = {
                                    selectedIndex = index
                                    onPlay(channel)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(if (compact) 54.dp else 72.dp))
                }
            }
        }

        UpdateChip(
            state = updateState,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = if (compact) 16.dp else 38.dp,
                    end = if (compact) 18.dp else 48.dp,
                ),
            onAction = {
                when (val state = updateState) {
                    is JoynUpdateState.Available -> updateManager.startDownload(state.info)
                    is JoynUpdateState.ReadyToInstall -> scope.launch {
                        updateManager.installDownloadedUpdate()
                    }
                    is JoynUpdateState.Error -> scope.launch { updateManager.checkForUpdates() }
                    else -> Unit
                }
            },
        )
    }
}

@Composable
private fun StatusText(text: String, compact: Boolean) {
    Text(
        text = text,
        modifier = Modifier.padding(
            horizontal = if (compact) 28.dp else 64.dp,
            vertical = 24.dp,
        ),
        color = Color.White,
        fontSize = if (compact) 18.sp else 22.sp,
    )
}

@Composable
private fun UpdateChip(
    state: JoynUpdateState,
    modifier: Modifier = Modifier,
    onAction: () -> Unit,
) {
    val label = when (state) {
        JoynUpdateState.Idle,
        JoynUpdateState.Checking,
        is JoynUpdateState.UpToDate,
        -> null
        is JoynUpdateState.Available -> "Update ${state.info.versionName}"
        is JoynUpdateState.Downloading -> state.progressPercent?.let { "Update $it %" } ?: "Update lädt …"
        is JoynUpdateState.ReadyToInstall -> "Update installieren"
        is JoynUpdateState.Error -> "Update erneut prüfen"
    } ?: return

    val actionable = state is JoynUpdateState.Available ||
        state is JoynUpdateState.ReadyToInstall ||
        state is JoynUpdateState.Error
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (focused) Color(0xFFF3F5F8) else Color(0xEE171B22))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color(0x775A6470),
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (actionable && event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onAction()
                    true
                } else false
            }
            .then(
                if (actionable) {
                    Modifier
                        .clickable(onClick = onAction)
                        .focusable()
                } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (focused) Color(0xFF11151B) else Color.White,
        )
    }
}

@Composable
private fun Hero(channel: JoynLiveChannel?, compact: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth(if (compact) 0.82f else 0.58f)
            .padding(horizontal = if (compact) 28.dp else 64.dp),
    ) {
        Text(
            text = "JOYN  ·  I LAUNCHER",
            color = Color(0xFFD7DBE3),
            fontSize = if (compact) 11.sp else 13.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
        Text(
            text = channel?.currentProgram?.title ?: channel?.title ?: "Joyn TV",
            color = Color.White,
            fontSize = if (compact) 30.sp else 42.sp,
            lineHeight = if (compact) 34.sp else 46.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        channel?.let {
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Text(
                text = listOfNotNull(it.title, it.quality).joinToString(" · "),
                color = Color(0xFFD7DBE3),
                fontSize = if (compact) 15.sp else 18.sp,
            )
            it.currentProgram?.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    color = Color(0xFFE3E6EC),
                    fontSize = if (compact) 14.sp else 16.sp,
                    lineHeight = if (compact) 18.sp else 22.sp,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LiveChannelCard(
    channel: JoynLiveChannel,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.055f else 1f, label = "channelFocus")
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .width(if (compact) 230.dp else 276.dp)
            .height(if (compact) 130.dp else 156.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(Color(0xFF161A21))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFFF4F6FA) else Color(0x66555C68),
                shape = shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onClick()
                    true
                } else false
            }
            .clickable {
                onFocused()
                onClick()
            }
            .focusable(),
    ) {
        AsyncImage(
            model = channel.currentProgram?.imageUrl ?: channel.logoUrl,
            contentDescription = channel.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = if (channel.currentProgram?.imageUrl != null) 0.72f else 0.32f,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2080A0E)))),
        )
        channel.logoUrl?.let { logo ->
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(if (compact) 10.dp else 12.dp)
                    .size(
                        width = if (compact) 72.dp else 82.dp,
                        height = if (compact) 32.dp else 36.dp,
                    ),
                contentScale = ContentScale.Fit,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(if (compact) 11.dp else 13.dp),
        ) {
            Text(
                text = channel.currentProgram?.title ?: channel.title,
                color = Color.White,
                fontSize = if (compact) 14.sp else 16.sp,
                lineHeight = if (compact) 17.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.currentProgram?.title != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = channel.title,
                    color = Color(0xFFD7DBE3),
                    fontSize = if (compact) 11.sp else 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
