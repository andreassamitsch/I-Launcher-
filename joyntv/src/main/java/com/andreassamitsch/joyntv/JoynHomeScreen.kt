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
import androidx.compose.ui.platform.LocalContext
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
    FAVORITES("Favoriten", null),
}

private val LIVE_COUNTRY_SECTIONS = listOf(
    JoynCountry.AT to "Österreich",
    JoynCountry.DE to "Deutschland",
    JoynCountry.CH to "Schweiz",
)

private val HomeSection.isLiveSection: Boolean
    get() = this == HomeSection.LIVE || this == HomeSection.FAVORITES

@Composable
internal fun JoynHomeScreen(
    repository: JoynRepository,
    updateManager: JoynUpdateManager,
    onPlayLive: (JoynLiveChannel) -> Unit,
    onOpenMedia: (JoynMediaItem) -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
) {
    val context = LocalContext.current
    val initialLiveRows = remember(repository) { repository.cachedLiveTvRows() }
    val initialCatalogue = remember(repository) { repository.cachedCatalogue("/neu-beliebt")?.forJoynUi() }
    val favoritesStore = remember(context) { JoynLiveFavoritesStore(context.applicationContext) }

    var section by remember { mutableStateOf(HomeSection.START) }
    var catalogue by remember { mutableStateOf(initialCatalogue) }
    var catalogueLoading by remember { mutableStateOf(initialCatalogue == null) }
    var catalogueError by remember { mutableStateOf<String?>(null) }
    var liveChannels by remember { mutableStateOf(initialLiveRows?.combined.orEmpty()) }
    var countryLiveChannels by remember { mutableStateOf(initialLiveRows?.byCountry.orEmpty()) }
    var liveError by remember { mutableStateOf<String?>(null) }
    var liveRefreshing by remember { mutableStateOf(false) }
    var liveRefreshCompleted by remember { mutableStateOf(false) }
    var favoriteIds by remember { mutableStateOf(favoritesStore.ids()) }
    var selectedMedia by remember { mutableStateOf(initialCatalogue?.lanes?.firstOrNull()?.items?.firstOrNull()) }
    var selectedLive by remember {
        mutableStateOf(initialLiveRows?.combined?.firstOrNull() ?: initialLiveRows?.byCountry?.values?.firstNotNullOfOrNull { it.firstOrNull() })
    }
    var prewarmLive by remember { mutableStateOf<JoynLiveChannel?>(null) }
    var artworkEnrichedSection by remember { mutableStateOf<HomeSection?>(null) }
    val updateState by updateManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val artworkDetailClient = remember(context) { JoynArtworkDetailClient(context.applicationContext) }

    fun applyLiveRows(rows: JoynLiveTvRows) {
        liveChannels = rows.combined
        countryLiveChannels = rows.byCountry
        val current = selectedLive?.id
        selectedLive = current?.let { id ->
            rows.combined.firstOrNull { it.id == id }
                ?: rows.byCountry.values.asSequence().flatten().firstOrNull { it.id == id }
        } ?: rows.combined.firstOrNull()
            ?: rows.byCountry.values.firstNotNullOfOrNull { it.firstOrNull() }
    }

    suspend fun refreshLiveRows() {
        if (liveRefreshing) return
        liveRefreshing = true
        liveError = null
        runCatching {
            withContext(Dispatchers.IO) {
                repository.loadLiveTvRowsAndPublish { partial ->
                    withContext(Dispatchers.Main.immediate) { applyLiveRows(partial) }
                }
            }
        }.onSuccess { rows ->
            applyLiveRows(rows)
            liveRefreshCompleted = true
        }.onFailure {
            liveError = it.message ?: it.javaClass.simpleName
        }
        liveRefreshing = false
    }

    fun toggleFavorite(channel: JoynLiveChannel) {
        favoriteIds = favoritesStore.toggle(channel.id)
        if (section == HomeSection.FAVORITES && channel.id !in favoriteIds) {
            selectedLive = liveChannels.firstOrNull { it.id in favoriteIds }
        }
    }

    LaunchedEffect(Unit) {
        updateManager.checkForUpdates()
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
        artworkEnrichedSection = null
        if (section.isLiveSection) {
            selectedMedia = null
            if (section == HomeSection.FAVORITES && selectedLive?.id !in favoriteIds) {
                selectedLive = liveChannels.firstOrNull { it.id in favoriteIds }
            }
            if (!liveRefreshCompleted) refreshLiveRows()
            return@LaunchedEffect
        }

        val path = section.path ?: "/neu-beliebt"
        val cached = repository.cachedCatalogue(path)?.forJoynUi()
        if (cached != null) {
            catalogue = cached
            selectedMedia = cached.lanes.firstOrNull()?.items?.firstOrNull()
        } else {
            catalogue = null
            selectedMedia = null
        }
        catalogueLoading = true
        catalogueError = null
        runCatching {
            withContext(Dispatchers.IO) { repository.loadCatalogue(path) }
        }.onSuccess { loaded ->
            val visible = loaded.forJoynUi()
            catalogue = visible
            selectedMedia = visible.lanes.firstOrNull()?.items?.firstOrNull()
        }.onFailure {
            if (catalogue == null) catalogueError = it.message ?: it.javaClass.simpleName
        }
        catalogueLoading = false
    }

    LaunchedEffect(section, favoriteIds, liveChannels) {
        if (section == HomeSection.FAVORITES && selectedLive?.id !in favoriteIds) {
            selectedLive = liveChannels.firstOrNull { it.id in favoriteIds }
        }
    }

    // Render cached/landing data immediately, then enrich only logo-only cards after the authoritative
    // refresh. The cache is intentionally stale-while-revalidate, so this work never blocks first paint.
    LaunchedEffect(catalogueLoading, section, catalogue?.title) {
        if (catalogueLoading || section.isLiveSection || artworkEnrichedSection == section) {
            return@LaunchedEffect
        }
        val initial = catalogue ?: return@LaunchedEffect
        val requestedSection = section
        artworkEnrichedSection = requestedSection
        val selectedId = selectedMedia?.id
        val enriched = runCatching {
            withContext(Dispatchers.IO) { artworkDetailClient.enrichPage(initial) }
        }.getOrNull() ?: return@LaunchedEffect
        if (section != requestedSection) return@LaunchedEffect
        catalogue = enriched
        selectedMedia = selectedId?.let { id ->
            enriched.lanes.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == id }
        } ?: enriched.lanes.firstOrNull()?.items?.firstOrNull()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF080A0E)),
    ) {
        // A phone is compact in both portrait and landscape. TVs stay in the spacious layout.
        val compact = maxWidth < 720.dp || maxHeight < 520.dp
        val heroMedia = if (section.isLiveSection) null else selectedMedia
        val heroLive = when {
            section.isLiveSection -> selectedLive
            section == HomeSection.START && selectedMedia == null -> selectedLive
            else -> null
        }
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
                    favorite = heroLive?.id in favoriteIds,
                    onToggleFavorite = heroLive?.let { channel -> { toggleFavorite(channel) } },
                )
            }

            when (section) {
                HomeSection.LIVE -> {
                    item {
                        SectionTitle("Live TV", compact)
                        when {
                            liveChannels.isNotEmpty() -> LiveRow(
                                channels = liveChannels,
                                compact = compact,
                                favoriteIds = favoriteIds,
                                onFocused = {
                                    selectedLive = it
                                    prewarmLive = it
                                },
                                onPlay = onPlayLive,
                            )
                            liveError != null -> ErrorText(liveError.orEmpty(), compact)
                            else -> StatusText("Live-Sender werden geladen …", compact)
                        }
                    }
                    LIVE_COUNTRY_SECTIONS.forEach { (country, label) ->
                        val channels = countryLiveChannels[country].orEmpty()
                        if (channels.isNotEmpty()) {
                            item(key = "live-country-${country.name}") {
                                SectionTitle(label, compact)
                                LiveRow(
                                    channels = channels,
                                    compact = compact,
                                    favoriteIds = favoriteIds,
                                    onFocused = {
                                        selectedLive = it
                                        prewarmLive = it
                                    },
                                    onPlay = onPlayLive,
                                )
                            }
                        }
                    }
                }

                HomeSection.FAVORITES -> {
                    val favorites = liveChannels.filter { it.id in favoriteIds }
                    item {
                        SectionTitle("Favoriten", compact)
                        when {
                            favorites.isNotEmpty() -> LiveRow(
                                channels = favorites,
                                compact = compact,
                                favoriteIds = favoriteIds,
                                onFocused = {
                                    selectedLive = it
                                    prewarmLive = it
                                },
                                onPlay = onPlayLive,
                            )
                            favoriteIds.isNotEmpty() && liveRefreshing ->
                                StatusText("Favoriten-Sender werden geladen …", compact)
                            else -> StatusText(
                                "Noch keine Favoriten. In Live TV einen Sender fokussieren und oben ☆ Zu Favoriten wählen.",
                                compact,
                            )
                        }
                    }
                }

                else -> {
                    if (section == HomeSection.START && liveChannels.isNotEmpty()) {
                        item {
                            SectionTitle("Jetzt live", compact)
                            LiveRow(
                                channels = liveChannels.take(16),
                                compact = compact,
                                favoriteIds = favoriteIds,
                                onFocused = {
                                    selectedLive = it
                                    prewarmLive = it
                                    selectedMedia = null
                                },
                                onPlay = onPlayLive,
                            )
                        }
                    }

                    if (!catalogue?.lanes.isNullOrEmpty()) {
                        catalogue.orEmptyLanes().forEach { lane ->
                            item(key = lane.id) {
                                lane.title.joynDisplayTitleOrNull()?.let { SectionTitle(it, compact) }
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
                    } else {
                        when {
                            catalogueLoading -> item { StatusText("Mediathek wird geladen …", compact) }
                            catalogueError != null -> item { ErrorText(catalogueError.orEmpty(), compact) }
                            else -> item { StatusText("Keine Mediathek-Inhalte verfügbar.", compact) }
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
    onSection: (HomeSection) -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
) {
    val padding = if (compact) 22.dp else 54.dp
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = if (compact) 14.dp else 24.dp),
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
private fun HeroArea(
    compact: Boolean,
    media: JoynMediaItem?,
    live: JoynLiveChannel?,
    favorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
) {
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
        if (live != null && onToggleFavorite != null) {
            Spacer(Modifier.height(12.dp))
            FavoriteChip(
                label = if (favorite) "★ Favorit entfernen" else "☆ Zu Favoriten",
                selected = favorite,
                onClick = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun FavoriteChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    selected -> Color(0xFF34301D)
                    else -> Color(0xD8171C24)
                },
            )
            .border(
                1.dp,
                when {
                    focused -> Color.White
                    selected -> Color(0xFFEACB68)
                    else -> Color(0xFF454E5A)
                },
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF11151B) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
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
    JoynMediaTile(
        item = item,
        compact = compact,
        cardHeight = if (compact) 124.dp else 152.dp,
        fallbackWidth = if (compact) 220.dp else 270.dp,
        onFocused = onFocused,
        onClick = onClick,
    )
}

@Composable
private fun LiveRow(
    channels: List<JoynLiveChannel>,
    compact: Boolean,
    favoriteIds: Set<String>,
    onFocused: (JoynLiveChannel) -> Unit,
    onPlay: (JoynLiveChannel) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = if (compact) 28.dp else 64.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            LiveCard(
                channel = channel,
                compact = compact,
                favorite = channel.id in favoriteIds,
                onFocused = onFocused,
            ) { onPlay(channel) }
        }
    }
    Spacer(Modifier.height(if (compact) 20.dp else 30.dp))
}

@Composable
private fun LiveCard(
    channel: JoynLiveChannel,
    compact: Boolean,
    favorite: Boolean,
    onFocused: (JoynLiveChannel) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val artwork = channel.currentProgram?.imageUrl
    var artworkAspect by remember(artwork) { mutableStateOf<Float?>(null) }
    val cardHeight = if (compact) 124.dp else 152.dp
    val fallbackWidth = if (compact) 220.dp else 270.dp
    val cardWidth = joynAdaptiveCardWidth(cardHeight, artworkAspect, fallbackWidth)

    Box(
        Modifier
            .width(cardWidth)
            .height(cardHeight)
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1A2029), Color(0xFF0E1117))),
            )
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
        if (artwork != null) {
            JoynAdaptiveArtwork(
                model = artwork,
                contentDescription = channel.title,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.9f,
                onAspectRatio = { artworkAspect = it },
            )
        }
        val hasProgramArtwork = artwork != null
        channel.logoUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = if (hasProgramArtwork) {
                    Modifier.align(Alignment.TopStart).padding(10.dp).size(
                        width = if (compact) 76.dp else 88.dp,
                        height = if (compact) 32.dp else 36.dp,
                    )
                } else {
                    Modifier.align(Alignment.Center).padding(start = 28.dp, end = 28.dp, bottom = 36.dp)
                        .fillMaxWidth().height(if (compact) 48.dp else 58.dp)
                },
                contentScale = ContentScale.Fit,
            )
        }
        if (favorite) {
            Text(
                "★",
                color = Color(0xFFFFD86A),
                fontSize = if (compact) 16.sp else 18.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(9.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9080A0E), Color(0xFF080A0E))),
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                channel.currentProgram?.title ?: channel.title,
                color = Color.White,
                fontSize = if (compact) 13.sp else 14.sp,
                lineHeight = if (compact) 16.sp else 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.currentProgram != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    channel.title,
                    color = Color(0xFFD7DBE3),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
