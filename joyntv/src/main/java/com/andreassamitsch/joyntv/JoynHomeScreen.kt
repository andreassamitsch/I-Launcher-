package com.andreassamitsch.joyntv

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
    TESTS("Tests", null),
}

private val LIVE_COUNTRY_SECTIONS = listOf(
    JoynCountry.AT to "Österreich",
    JoynCountry.DE to "Deutschland",
    JoynCountry.CH to "Schweiz",
)

@Composable
internal fun JoynHomeScreen(
    repository: JoynRepository,
    updateManager: JoynUpdateManager,
    resumeKey: Int,
    onPlayLive: (JoynLiveChannel) -> Unit,
    onOpenMedia: (JoynMediaItem) -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
) {
    val context = LocalContext.current
    val initialLiveRows = remember(repository) { repository.cachedLiveTvRows() }
    val initialCatalogue = remember(repository) { repository.cachedCatalogue("/neu-beliebt")?.forJoynUi() }
    val homeCache = remember(context) { JoynHomeCache(context.applicationContext) }
    val liveFavoritesStore = remember(context) { JoynLiveFavoritesStore(context.applicationContext) }
    val mediaFavoritesStore = remember(context) { JoynMediaFavoritesStore(context.applicationContext) }
    val continueWatchingStore = remember(context) { JoynContinueWatchingStore(context.applicationContext) }
    val watchNextPublisher = remember(context) { JoynWatchNextPublisher(context.applicationContext) }

    var section by remember { mutableStateOf(HomeSection.START) }
    var catalogue by remember { mutableStateOf(initialCatalogue) }
    var catalogueLoading by remember { mutableStateOf(initialCatalogue == null) }
    var catalogueError by remember { mutableStateOf<String?>(null) }
    var liveChannels by remember { mutableStateOf(initialLiveRows?.combined.orEmpty()) }
    var countryLiveChannels by remember { mutableStateOf(initialLiveRows?.byCountry.orEmpty()) }
    var liveError by remember { mutableStateOf<String?>(null) }
    var liveRefreshing by remember { mutableStateOf(false) }
    // Cache is only the instant first paint. Refresh Live TV once per process/session so current/next
    // programme data and channel availability never become permanently stale across app launches.
    var liveRefreshCompleted by remember { mutableStateOf(false) }
    var liveFavoriteIds by remember { mutableStateOf(liveFavoritesStore.ids()) }
    var mediaFavorites by remember { mutableStateOf(mediaFavoritesStore.items()) }
    var continueWatching by remember { mutableStateOf(continueWatchingStore.items()) }
    val mediaFavoriteKeys = mediaFavorites
        .mapTo(linkedSetOf()) { JoynMediaFavoritesStore.favoriteKey(it) }
    var selectedMedia by remember { mutableStateOf(initialCatalogue?.lanes?.firstOrNull()?.items?.firstOrNull()) }
    var selectedLive by remember {
        mutableStateOf(
            initialLiveRows?.combined?.firstOrNull()
                ?: initialLiveRows?.byCountry?.values?.firstNotNullOfOrNull { it.firstOrNull() },
        )
    }
    var prewarmLive by remember { mutableStateOf<JoynLiveChannel?>(null) }
    var artworkEnrichedSection by remember { mutableStateOf<HomeSection?>(null) }
    val updateState by updateManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val artworkDetailClient = remember(context) { JoynArtworkDetailClient(context.applicationContext) }

    /**
     * During progressive refresh an empty country means "not loaded yet" or "temporarily failed".
     * Keep an already cached country visible until fresh data for that country arrives. This also
     * prevents one transient DE/CH route failure from visually deleting the previous good list.
     */
    fun applyLiveRows(rows: JoynLiveTvRows, preserveMissing: Boolean): JoynLiveTvRows {
        val previousCombined = liveChannels
        val previousByCountry = countryLiveChannels
        val freshCountries = JoynCountry.entries.filterTo(linkedSetOf()) { country ->
            rows.byCountry[country].orEmpty().isNotEmpty()
        }

        val displayRows = if (!preserveMissing) {
            rows
        } else {
            val mergedByCountry = JoynCountry.entries.associateWith { country ->
                rows.byCountry[country].orEmpty().ifEmpty { previousByCountry[country].orEmpty() }
            }
            val freshIds = rows.combined.mapTo(hashSetOf()) { it.id }
            val retained = previousCombined.filter { existing ->
                existing.id !in freshIds && liveCountryFromCombinedId(existing.id) !in freshCountries
            }
            JoynLiveTvRows(
                combined = rows.combined + retained,
                byCountry = mergedByCountry,
            )
        }

        liveChannels = displayRows.combined
        countryLiveChannels = displayRows.byCountry

        if (!(section == HomeSection.FAVORITES && selectedMedia != null)) {
            val current = selectedLive?.id
            selectedLive = current?.let { id ->
                displayRows.combined.firstOrNull { it.id == id }
                    ?: displayRows.byCountry.values.asSequence().flatten().firstOrNull { it.id == id }
            } ?: displayRows.combined.firstOrNull()
                ?: displayRows.byCountry.values.firstNotNullOfOrNull { it.firstOrNull() }
        }
        return displayRows
    }

    suspend fun refreshLiveRows() {
        if (liveRefreshing) return
        liveRefreshing = true
        liveError = null
        runCatching {
            withContext(Dispatchers.IO) {
                repository.loadLiveTvRowsAndPublish { partial ->
                    withContext(Dispatchers.Main.immediate) {
                        applyLiveRows(partial, preserveMissing = true)
                    }
                }
            }
        }.onSuccess { rows ->
            val displayRows = applyLiveRows(rows, preserveMissing = true)
            val complete = JoynCountry.entries.all { country -> rows.byCountry[country].orEmpty().isNotEmpty() }
            liveRefreshCompleted = complete

            // Repository intentionally writes the authoritative network result. When one foreign
            // country failed transiently, put the retained last-good rows back into the UI cache so
            // the next app start does not regress to an AT-only list.
            if (!complete && displayRows != rows) {
                homeCache.writeLiveRows(displayRows)
            }
        }.onFailure {
            liveError = it.message ?: it.javaClass.simpleName
            liveRefreshCompleted = false
        }
        liveRefreshing = false
    }

    fun toggleLiveFavorite(channel: JoynLiveChannel) {
        liveFavoriteIds = liveFavoritesStore.toggle(channel.id)
        if (section == HomeSection.FAVORITES && channel.id !in liveFavoriteIds && selectedMedia == null) {
            selectedLive = liveChannels.firstOrNull { it.id in liveFavoriteIds }
            if (selectedLive == null) selectedMedia = mediaFavorites.firstOrNull()
        }
    }

    fun toggleMediaFavorite(item: JoynMediaItem) {
        mediaFavorites = mediaFavoritesStore.toggle(item)
        val key = JoynMediaFavoritesStore.favoriteKey(item)
        val stillFavorite = mediaFavorites.any { JoynMediaFavoritesStore.favoriteKey(it) == key }
        if (section == HomeSection.FAVORITES && !stillFavorite && selectedMedia?.let(JoynMediaFavoritesStore::favoriteKey) == key) {
            selectedMedia = mediaFavorites.firstOrNull()
            if (selectedMedia == null) {
                selectedLive = liveChannels.firstOrNull { it.id in liveFavoriteIds }
            }
        }
    }

    LaunchedEffect(resumeKey) {
        val refreshed = withContext(Dispatchers.IO) {
            val loggedIn = runCatching { repository.accountState(refreshRemote = false).loggedIn }
                .getOrDefault(false)
            if (loggedIn) {
                continueWatchingStore.pendingDeleteIds().forEach { assetId ->
                    val cleared = runCatching { repository.setResumePosition(assetId, 0) }.getOrDefault(false)
                    if (cleared) continueWatchingStore.clearPendingDelete(assetId)
                }
                continueWatchingStore.dirtyItems().forEach { entry ->
                    val synced = runCatching {
                        repository.setResumePosition(
                            entry.assetId,
                            (entry.positionMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        )
                    }.getOrDefault(false)
                    if (synced) continueWatchingStore.markSynced(entry.assetId)
                }
                val remote = runCatching { repository.loadContinueWatching() }.getOrNull()
                if (remote != null) continueWatchingStore.replaceFromRemote(remote)
                else continueWatchingStore.items()
            } else {
                continueWatchingStore.items()
            }
        }
        continueWatching = refreshed
        watchNextPublisher.sync(refreshed)
    }

    fun removeContinueWatching(entry: JoynContinueWatchingEntry) {
        continueWatching = continueWatchingStore.remove(entry.assetId, pendingRemoteDelete = true)
        watchNextPublisher.remove(entry.assetId)
        scope.launch {
            val cleared = withContext(Dispatchers.IO) {
                runCatching { repository.setResumePosition(entry.assetId, 0) }.getOrDefault(false)
            }
            if (cleared) continueWatchingStore.clearPendingDelete(entry.assetId)
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

    // Progressive AT/DE/CH loading changes the process-wide Joyn route. Never run focus-prewarm at
    // the same time: the old behaviour could switch the route back to the focused AT card between
    // ensureJoynCountryRouting(DE/CH) and the corresponding GraphQL request, leaving DE/CH empty.
    LaunchedEffect(prewarmLive?.id, liveRefreshing) {
        if (liveRefreshing) return@LaunchedEffect
        val channel = prewarmLive ?: return@LaunchedEffect
        delay(LIVE_ROUTE_PREWARM_DELAY_MS)
        withContext(Dispatchers.IO) {
            repository.prepareLiveChannel(channel.id)
        }
    }

    LaunchedEffect(section) {
        artworkEnrichedSection = null

        when (section) {
            HomeSection.LIVE -> {
                selectedMedia = null
                if (!liveRefreshCompleted) refreshLiveRows()
                return@LaunchedEffect
            }

            HomeSection.FAVORITES -> {
                val firstMedia = mediaFavorites.firstOrNull()
                if (firstMedia != null) {
                    selectedMedia = firstMedia
                    selectedLive = null
                    prewarmLive = null
                } else {
                    selectedMedia = null
                    selectedLive = liveChannels.firstOrNull { it.id in liveFavoriteIds }
                }
                if (liveFavoriteIds.isNotEmpty() && !liveRefreshCompleted) refreshLiveRows()
                return@LaunchedEffect
            }

            HomeSection.TESTS -> {
                selectedMedia = null
                selectedLive = null
                prewarmLive = null
                return@LaunchedEffect
            }

            else -> Unit
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

    LaunchedEffect(section, liveFavoriteIds, mediaFavorites, liveChannels) {
        if (section != HomeSection.FAVORITES) return@LaunchedEffect

        val selectedMediaKey = selectedMedia?.let(JoynMediaFavoritesStore::favoriteKey)
        val mediaStillValid = selectedMediaKey != null && mediaFavorites.any {
            JoynMediaFavoritesStore.favoriteKey(it) == selectedMediaKey
        }
        val liveStillValid = selectedLive?.id in liveFavoriteIds

        if (!mediaStillValid && !liveStillValid) {
            selectedMedia = mediaFavorites.firstOrNull()
            selectedLive = if (selectedMedia == null) {
                liveChannels.firstOrNull { it.id in liveFavoriteIds }
            } else {
                null
            }
        }
    }

    // Render cached/landing data immediately, then enrich only logo-only cards after the authoritative
    // refresh. Favorites already contain a persistent visual/navigation snapshot and do not need to
    // block their first paint on another enrichment round.
    LaunchedEffect(catalogueLoading, section, catalogue?.title) {
        if (
            catalogueLoading ||
            section == HomeSection.LIVE ||
            section == HomeSection.FAVORITES ||
            section == HomeSection.TESTS ||
            artworkEnrichedSection == section
        ) {
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
        val compact = maxWidth < 720.dp || maxHeight < 520.dp
        val heroMedia = when (section) {
            HomeSection.LIVE, HomeSection.TESTS -> null
            else -> selectedMedia
        }
        val heroLive = when {
            section == HomeSection.LIVE -> selectedLive
            section == HomeSection.FAVORITES && heroMedia == null -> selectedLive
            section == HomeSection.START && heroMedia == null -> selectedLive
            else -> null
        }
        val heroImage = heroMedia?.backdropUrl ?: heroMedia?.imageUrl
            ?: heroLive?.currentProgram?.imageUrl ?: heroLive?.logoUrl
        val heroFavorite = when {
            heroMedia != null -> JoynMediaFavoritesStore.favoriteKey(heroMedia) in mediaFavoriteKeys
            heroLive != null -> heroLive.id in liveFavoriteIds
            else -> false
        }
        val heroToggleFavorite: (() -> Unit)? = when {
            heroMedia != null -> heroMedia.let { item -> { toggleMediaFavorite(item) } }
            heroLive != null -> heroLive.let { channel -> { toggleLiveFavorite(channel) } }
            else -> null
        }

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
            if (section != HomeSection.TESTS) {
                item {
                    HeroArea(
                        compact = compact,
                        media = heroMedia,
                        live = heroLive,
                        favorite = heroFavorite,
                        onToggleFavorite = heroToggleFavorite,
                    )
                }
            }

            when (section) {
                HomeSection.TESTS -> {
                    item(key = "tests") {
                        JoynTestsPanel(compact = compact)
                    }
                }

                HomeSection.LIVE -> {
                    item {
                        SectionTitle("Live TV", compact)
                        when {
                            liveChannels.isNotEmpty() -> LiveRow(
                                channels = liveChannels,
                                compact = compact,
                                favoriteIds = liveFavoriteIds,
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
                        item(key = "live-country-${country.name}") {
                            SectionTitle(label, compact)
                            when {
                                channels.isNotEmpty() -> LiveRow(
                                    channels = channels,
                                    compact = compact,
                                    favoriteIds = liveFavoriteIds,
                                    onFocused = {
                                        selectedLive = it
                                        prewarmLive = it
                                    },
                                    onPlay = onPlayLive,
                                )
                                liveRefreshing -> StatusText("Sender werden geladen …", compact)
                                else -> StatusText("Sender aktuell nicht verfügbar.", compact)
                            }
                        }
                    }
                }

                HomeSection.FAVORITES -> {
                    val favoriteLiveChannels = liveChannels.filter { it.id in liveFavoriteIds }
                    when {
                        mediaFavorites.isEmpty() && favoriteLiveChannels.isEmpty() -> item {
                            val text = if (liveFavoriteIds.isNotEmpty() && liveRefreshing) {
                                "Live-Favoriten werden geladen …"
                            } else {
                                "Noch keine Favoriten. Serie, Film, Sendung oder Live-Sender fokussieren und oben ☆ Zu Favoriten wählen."
                            }
                            StatusText(text, compact)
                        }

                        else -> {
                            if (mediaFavorites.isNotEmpty()) {
                                item(key = "favorite-media") {
                                    SectionTitle("Serien, Filme & mehr", compact)
                                    MediaRow(
                                        items = mediaFavorites,
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
                            if (favoriteLiveChannels.isNotEmpty()) {
                                item(key = "favorite-live") {
                                    SectionTitle("Live TV", compact)
                                    LiveRow(
                                        channels = favoriteLiveChannels,
                                        compact = compact,
                                        favoriteIds = liveFavoriteIds,
                                        onFocused = {
                                            selectedMedia = null
                                            selectedLive = it
                                            prewarmLive = it
                                        },
                                        onPlay = onPlayLive,
                                    )
                                }
                            } else if (liveFavoriteIds.isNotEmpty() && liveRefreshing) {
                                item(key = "favorite-live-loading") {
                                    StatusText("Live-Favoriten werden geladen …", compact)
                                }
                            }
                        }
                    }
                }

                else -> {
                    if (section == HomeSection.START && continueWatching.isNotEmpty()) {
                        item(key = "continue-watching") {
                            SectionTitle("Weiterschauen", compact)
                            ContinueWatchingRow(
                                entries = continueWatching,
                                compact = compact,
                                onFocused = {
                                    selectedMedia = it.media
                                    selectedLive = null
                                    prewarmLive = null
                                },
                                onOpen = { onOpenMedia(it.media) },
                                onRemove = ::removeContinueWatching,
                            )
                        }
                    }

                    if (section == HomeSection.START && liveChannels.isNotEmpty()) {
                        item {
                            SectionTitle("Jetzt live", compact)
                            LiveRow(
                                channels = liveChannels.take(16),
                                compact = compact,
                                favoriteIds = liveFavoriteIds,
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

private fun liveCountryFromCombinedId(id: String): JoynCountry? {
    if (!id.startsWith("multi:")) return null
    return runCatching {
        JoynCountry.valueOf(id.removePrefix("multi:").substringBefore(':'))
    }.getOrNull()
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
                JoynMediaType.COMPILATION -> "Sendung"
                JoynMediaType.CHANNEL -> "Mediathek"
                JoynMediaType.COLLECTION -> "Sammlung"
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
        if ((media != null || live != null) && onToggleFavorite != null) {
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
private fun ContinueWatchingRow(
    entries: List<JoynContinueWatchingEntry>,
    compact: Boolean,
    onFocused: (JoynContinueWatchingEntry) -> Unit,
    onOpen: (JoynContinueWatchingEntry) -> Unit,
    onRemove: (JoynContinueWatchingEntry) -> Unit,
) {
    val cardWidth = if (compact) 220.dp else 270.dp
    val cardHeight = if (compact) 124.dp else 152.dp
    LazyRow(
        contentPadding = PaddingValues(
            horizontal = if (compact) 28.dp else 64.dp,
            vertical = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        items(entries, key = { it.assetId }) { entry ->
            Box(
                Modifier
                    .width(cardWidth)
                    .height(cardHeight),
            ) {
                JoynMediaTile(
                    item = entry.media,
                    compact = compact,
                    cardHeight = cardHeight,
                    fallbackWidth = cardWidth,
                    fixedWidth = cardWidth,
                    displayTitle = entry.media.seriesTitle?.takeIf(String::isNotBlank)
                        ?: entry.media.title,
                    subtitle = if (entry.media.type == JoynMediaType.EPISODE) {
                        listOfNotNull(
                            entry.media.seasonNumber?.let { "S$it" },
                            entry.media.episodeNumber?.let { "F$it" },
                            entry.media.title.takeIf { it != entry.media.seriesTitle },
                        ).joinToString(" · ").takeIf(String::isNotBlank)
                    } else null,
                    onFocused = { onFocused(entry) },
                    onClick = { onOpen(entry) },
                )

                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x66000000)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(entry.progress.coerceIn(0.01f, 1f))
                            .height(4.dp)
                            .background(Color.White),
                    )
                }

                ContinueRemoveButton(
                    compact = compact,
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                    onClick = { onRemove(entry) },
                )
            }
        }
    }
    Spacer(Modifier.height(if (compact) 20.dp else 30.dp))
}

@Composable
private fun ContinueRemoveButton(
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (focused) Color.White else Color(0xCC10141A))
            .border(1.dp, if (focused) Color.White else Color(0xFF606A76), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(
                horizontal = if (compact) 9.dp else 10.dp,
                vertical = if (compact) 5.dp else 6.dp,
            ),
    ) {
        Text(
            "✕",
            color = if (focused) Color(0xFF11151B) else Color.White,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
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
        items(items, key = { "${it.type.name}:${it.id}" }) { item ->
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
    val actionable = state is JoynUpdateState.Available ||
        state is JoynUpdateState.ReadyToInstall ||
        state is JoynUpdateState.Error
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
