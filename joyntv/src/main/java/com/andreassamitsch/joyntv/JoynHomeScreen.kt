package com.andreassamitsch.joyntv

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class HomeSection(val label: String, val path: String?) {
    START("Start", "/neu-beliebt"),
    SERIES("Serien", "/serien"),
    MOVIES("Filme", "/filme"),
    SPORT("Sport", "/sport"),
    LIVE("Live TV", null),
}

@Composable
internal fun JoynHomeScreen(
    repository: JoynRepository,
    updateManager: JoynUpdateManager,
    onPlayLive: (JoynLiveChannel) -> Unit,
    onOpenMedia: (JoynMediaItem) -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
) {
    var section by remember { mutableStateOf(HomeSection.START) }
    var catalogue by remember { mutableStateOf<JoynCataloguePage?>(null) }
    var catalogueLoading by remember { mutableStateOf(true) }
    var catalogueError by remember { mutableStateOf<String?>(null) }
    var liveChannels by remember { mutableStateOf<List<JoynLiveChannel>>(emptyList()) }
    var liveError by remember { mutableStateOf<String?>(null) }
    var selectedMedia by remember { mutableStateOf<JoynMediaItem?>(null) }
    var selectedLive by remember { mutableStateOf<JoynLiveChannel?>(null) }
    var prewarmLive by remember { mutableStateOf<JoynLiveChannel?>(null) }
    var account by remember { mutableStateOf(JoynAccountState(loggedIn = false)) }
    val updateState by updateManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        updateManager.checkForUpdates()
        runCatching {
            withContext(Dispatchers.IO) { repository.loadLiveChannelsAndPublish() }
        }.onSuccess {
            liveChannels = it
            if (selectedLive == null) selectedLive = it.firstOrNull()
        }.onFailure { liveError = it.message ?: it.javaClass.simpleName }
        runCatching {
            withContext(Dispatchers.IO) { repository.accountState(refreshRemote = true) }
        }.onSuccess { account = it }
    }

    LaunchedEffect(updateState) {
        while (updateState is JoynUpdateState.Downloading) {
            delay(700)
            updateManager.refreshDownloadState()
        }
    }

    // TV users normally focus a card shortly before pressing OK. Use that small dwell time to
    // prepare the target country's already-approved Mysterium route. Moving focus again cancels the
    // debounce before any unnecessary country switch starts.
    LaunchedEffect(prewarmLive?.id) {
        val channel = prewarmLive ?: return@LaunchedEffect
        delay(LIVE_ROUTE_PREWARM_DELAY_MS)
        withContext(Dispatchers.IO) {
            repository.prepareLiveChannel(channel.id)
        }
    }

    LaunchedEffect(section) {
        if (section == HomeSection.LIVE) return@LaunchedEffect
        catalogueLoading = true
        catalogueError = null
        selectedMedia = null
        runCatching {
            withContext(Dispatchers.IO) { repository.loadCatalogue(section.path ?: "/neu-beliebt") }
        }.onSuccess {
            catalogue = it
            selectedMedia = it.lanes.firstOrNull()?.items?.firstOrNull()
        }.onFailure {
            catalogue = null
            catalogueError = it.message ?: it.javaClass.simpleName
        }
        catalogueLoading = false
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF080A0E)),
    ) {
        val compact = maxHeight < 520.dp
        val heroMedia = if (section == HomeSection.LIVE) null else selectedMedia
        val heroLive = if (section == HomeSection.LIVE) selectedLive else null
        val heroImage = heroMedia?.backdropUrl ?: heroMedia?.imageUrl
            ?: heroLive?.currentProgram?.imageUrl ?: heroLive?.logoUrl

        AsyncImage(
            model = heroImage,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.34f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x66080A0E),
                    0.42f to Color(0xD4080A0E),
                    1f to Color(0xFF080A0E),
                ),
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (compact) 44.dp else 64.dp),
        ) {
            item {
                HeaderNavigation(
                    compact = compact,
                    selected = section,
                    account = account,
                    onSection = { section = it },
                    onSearch = onSearch,
                    onAccount = onAccount,
                )
            }
            item {
                HeroArea(
                    compact = compact,
                    media = heroMedia,
                    live = heroLive,
                )
            }

            if (section == HomeSection.LIVE) {
                item {
                    SectionTitle("Live TV", compact)
                    when {
                        liveError != null -> ErrorText(liveError.orEmpty(), compact)
                        liveChannels.isEmpty() -> StatusText("Live-Sender werden geladen …", compact)
                        else -> LiveRow(
                            channels = liveChannels,
                            compact = compact,
                            onFocused = {
                                selectedLive = it
                                prewarmLive = it
                            },
                            onPlay = onPlayLive,
                        )
                    }
                }
            } else {
                if (section == HomeSection.START && liveChannels.isNotEmpty()) {
                    item {
                        SectionTitle("Jetzt live", compact)
                        LiveRow(
                            channels = liveChannels.take(16),
                            compact = compact,
                            onFocused = {
                                selectedLive = it
                                prewarmLive = it
                                selectedMedia = null
                            },
                            onPlay = onPlayLive,
                        )
                    }
                }

                when {
                    catalogueLoading -> item { StatusText("Mediathek wird geladen …", compact) }
                    catalogueError != null -> item { ErrorText(catalogueError.orEmpty(), compact) }
                    catalogue?.lanes.isNullOrEmpty() -> item { StatusText("Keine Mediathek-Inhalte verfügbar.", compact) }
                    else -> {
                        catalogue.orEmptyLanes().forEach { lane ->
                            item(key = lane.id) {
                                SectionTitle(lane.title, compact)
                                MediaRow(
                                    items = lane.items,
                                    compact = compact,
                                    onFocused = {
                                        selectedMedia = it
                                        selectedLive = null
                                        prewarmLive = null
                                    },
                                    onOpen = onOpenMedia,
                                )
                            }
                        }
                    }
                }
            }
        }

        UpdateChipHome(
            state = updateState,
            compact = compact,
            modifier = Modifier.align(Alignment.BottomEnd).padding(
                end = if (compact) 18.dp else 42.dp,
                bottom = if (compact) 14.dp else 24.dp,
            ),
            onAction = {
                when (val state = updateState) {
                    is JoynUpdateState.Available -> updateManager.startDownload(state.info)
                    is JoynUpdateState.ReadyToInstall -> scope.launch { updateManager.installDownloadedUpdate() }
                    is JoynUpdateState.Error -> scope.launch { updateManager.checkForUpdates() }
                    else -> Unit
                }
            },
        )
    }
}

private fun JoynCataloguePage?.orEmptyLanes(): List<JoynLane> = this?.lanes.orEmpty()

@Composable
private fun HeaderNavigation(
    compact: Boolean,
    selected: HomeSection,
    account: JoynAccountState,
    onSection: (HomeSection) -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
) {
    val padding = if (compact) 22.dp else 54.dp
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 18.dp else 28.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = padding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "JOYN  ·  I LAUNCHER",
                color = Color(0xFFD7DBE3),
                fontSize = if (compact) 10.sp else 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (account.loggedIn) "Konto ✓" else "Gast",
                color = Color(0xFF9EA6B2),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(HomeSection.entries, key = { it.name }) { item ->
                NavChip(item.label, selected == item) { onSection(item) }
            }
            item { NavChip("Suche", false, onSearch) }
            item { NavChip("Konto", false, onAccount) }
        }
    }
}

@Composable
private fun NavChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected || focused) Color.White else Color(0xD8171C24))
            .border(1.dp, if (focused) Color.White else Color(0xFF454E5A), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected || focused) Color(0xFF11151B) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HeroArea(compact: Boolean, media: JoynMediaItem?, live: JoynLiveChannel?) {
    val padding = if (compact) 28.dp else 64.dp
    Column(
        Modifier.fillMaxWidth(if (compact) 0.88f else 0.62f).padding(
            start = padding,
            end = padding,
            top = if (compact) 24.dp else 42.dp,
            bottom = if (compact) 26.dp else 54.dp,
        ),
    ) {
        val title = media?.title ?: live?.currentProgram?.title ?: live?.title ?: "Joyn"
        val mediaLogo = media?.logoUrl?.takeIf(String::isNotBlank)
        if (mediaLogo != null) {
            AsyncImage(
                model = mediaLogo,
                contentDescription = title,
                modifier = Modifier.width(if (compact) 140.dp else 210.dp).height(if (compact) 46.dp else 68.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                title,
                color = Color.White,
                fontSize = if (compact) 29.sp else 42.sp,
                lineHeight = if (compact) 33.sp else 46.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val meta = when {
            media != null -> when (media.type) {
                JoynMediaType.SERIES -> "Serie"
                JoynMediaType.MOVIE -> "Film"
                JoynMediaType.EPISODE -> listOfNotNull(
                    media.seasonNumber?.let { "S$it" },
                    media.episodeNumber?.let { "F$it" },
                ).joinToString(" · ").ifBlank { "Folge" }
                JoynMediaType.SPORT -> "Sport"
                else -> "Joyn"
            }
            live != null -> listOfNotNull(live.title, live.quality).joinToString(" · ")
            else -> null
        }
        meta?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFD7DBE3), fontSize = if (compact) 14.sp else 17.sp)
        }
        val description = media?.description ?: live?.currentProgram?.subtitle
        description?.takeIf(String::isNotBlank)?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = Color(0xFFE2E5EA),
                fontSize = if (compact) 13.sp else 15.sp,
                lineHeight = if (compact) 18.sp else 21.sp,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, compact: Boolean) {
    Text(
        title,
        color = Color.White,
        fontSize = if (compact) 18.sp else 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            start = if (compact) 28.dp else 64.dp,
            end = if (compact) 28.dp else 64.dp,
            top = 10.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun MediaRow(
    items: List<JoynMediaItem>,
    compact: Boolean,
    onFocused: (JoynMediaItem) -> Unit,
    onOpen: (JoynMediaItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(
            horizontal = if (compact) 28.dp else 64.dp,
            vertical = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        items(items, key = { it.id }) { item ->
            MediaCard(item, compact, onFocused) { onOpen(item) }
        }
    }
    Spacer(Modifier.height(if (compact) 20.dp else 30.dp))
}

@Composable
private fun MediaCard(
    item: JoynMediaItem,
    compact: Boolean,
    onFocused: (JoynMediaItem) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "mediaFocus")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .width(if (compact) 220.dp else 270.dp)
            .height(if (compact) 124.dp else 152.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Color(0xFF161A21))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF46505D), shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused(item)
            }
            .clickable {
                onFocused(item)
                onClick()
            }
            .focusable(),
    ) {
        AsyncImage(
            model = item.backdropUrl ?: item.imageUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.84f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xF5080A0E))),
            ),
        )
        item.logoUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(
                    width = if (compact) 84.dp else 100.dp,
                    height = if (compact) 34.dp else 40.dp,
                ),
                contentScale = ContentScale.Fit,
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                item.title,
                color = Color.White,
                fontSize = if (compact) 14.sp else 15.sp,
                lineHeight = if (compact) 17.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if ("SVOD" in item.licenseTypes || "PLUS" in item.markings || "PREMIUM" in item.markings) {
                Spacer(Modifier.height(2.dp))
                Text("PLUS+", color = Color(0xFFD7DBE3), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LiveRow(
    channels: List<JoynLiveChannel>,
    compact: Boolean,
    onFocused: (JoynLiveChannel) -> Unit,
    onPlay: (JoynLiveChannel) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = if (compact) 28.dp else 64.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            LiveCard(channel, compact, onFocused) { onPlay(channel) }
        }
    }
    Spacer(Modifier.height(if (compact) 20.dp else 30.dp))
}

@Composable
private fun LiveCard(
    channel: JoynLiveChannel,
    compact: Boolean,
    onFocused: (JoynLiveChannel) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .width(if (compact) 220.dp else 270.dp)
            .height(if (compact) 124.dp else 152.dp)
            .clip(shape)
            .background(Color(0xFF161A21))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF46505D), shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused(channel)
            }
            .clickable {
                onFocused(channel)
                onClick()
            }
            .focusable(),
    ) {
        AsyncImage(
            model = channel.currentProgram?.imageUrl ?: channel.logoUrl,
            contentDescription = channel.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = if (channel.currentProgram?.imageUrl != null) 0.84f else 0.32f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xF5080A0E))),
            ),
        )
        channel.logoUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(
                    width = if (compact) 76.dp else 88.dp,
                    height = if (compact) 32.dp else 36.dp,
                ),
                contentScale = ContentScale.Fit,
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                channel.currentProgram?.title ?: channel.title,
                color = Color.White,
                fontSize = if (compact) 14.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(channel.title, color = Color(0xFFD7DBE3), fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatusText(text: String, compact: Boolean) {
    Text(
        text,
        color = Color(0xFFD7DBE3),
        fontSize = if (compact) 15.sp else 17.sp,
        modifier = Modifier.padding(horizontal = if (compact) 28.dp else 64.dp, vertical = 20.dp),
    )
}

@Composable
private fun ErrorText(text: String, compact: Boolean) {
    Text(
        text.take(700),
        color = Color(0xFFFFC5C5),
        fontSize = if (compact) 13.sp else 14.sp,
        modifier = Modifier.padding(horizontal = if (compact) 28.dp else 64.dp, vertical = 20.dp),
    )
}

@Composable
private fun UpdateChipHome(
    state: JoynUpdateState,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onAction: () -> Unit,
) {
    val label = when (state) {
        JoynUpdateState.Idle, JoynUpdateState.Checking, is JoynUpdateState.UpToDate -> null
        is JoynUpdateState.Available -> "Update ${state.info.versionName}"
        is JoynUpdateState.Downloading -> state.progressPercent?.let { "Update $it %" } ?: "Update lädt …"
        is JoynUpdateState.ReadyToInstall -> "Update installieren"
        is JoynUpdateState.Error -> "Update prüfen"
    } ?: return
    val actionable = state is JoynUpdateState.Available || state is JoynUpdateState.ReadyToInstall || state is JoynUpdateState.Error
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (focused) Color.White else Color(0xF0181D25))
            .border(1.dp, if (focused) Color.White else Color(0xFF4D5663), shape)
            .onFocusChanged { focused = it.isFocused }
            .then(if (actionable) Modifier.clickable(onClick = onAction).focusable() else Modifier)
            .padding(horizontal = if (compact) 13.dp else 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF11151B) else Color.White,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private const val LIVE_ROUTE_PREWARM_DELAY_MS = 180L
