package com.andreassamitsch.ilauncher.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.apps.InstalledAppsRepository
import com.andreassamitsch.ilauncher.data.epg.EpgRepository
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifRepository
import com.andreassamitsch.ilauncher.data.search.SearchRepository
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.data.tv.PreviewChannelPreferences
import com.andreassamitsch.ilauncher.data.tv.PreviewChannelsRepository
import com.andreassamitsch.ilauncher.data.tv.WatchNextEnrichmentRepository
import com.andreassamitsch.ilauncher.data.tv.WatchNextRepository
import com.andreassamitsch.ilauncher.data.tv.WatchNextSourcePreferences
import com.andreassamitsch.ilauncher.data.update.UpdateManager
import com.andreassamitsch.ilauncher.data.update.UpdateState
import com.andreassamitsch.ilauncher.data.youtube.YouTubeLauncher
import com.andreassamitsch.ilauncher.model.AppContentChannelsLoadResult
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind
import com.andreassamitsch.ilauncher.model.WatchNextLoadResult
import com.andreassamitsch.ilauncher.system.TvProviderPermissionManager
import com.andreassamitsch.ilauncher.ui.apps.AppsScreen
import com.andreassamitsch.ilauncher.ui.details.DetailsScreen
import com.andreassamitsch.ilauncher.ui.epg.EpgScreen
import com.andreassamitsch.ilauncher.ui.home.HomeScreen
import com.andreassamitsch.ilauncher.ui.livetv.LiveTvPlayerScreen
import com.andreassamitsch.ilauncher.ui.livetv.LiveTvScreen
import com.andreassamitsch.ilauncher.ui.search.SearchScreen
import com.andreassamitsch.ilauncher.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LauncherSection(val label: String) {
    Home("Home"),
    Search("Suche"),
    LiveTv("Live TV"),
    Epg("EPG"),
    Apps("Apps"),
    Settings("Einstellungen"),
}

private const val TMDB_ENRICHMENT_BATCH_SIZE = 4
private const val TMDB_ENRICHMENT_RETRY_DELAY_MILLIS = 1_500L
private const val LOCAL_SEARCH_DEBOUNCE_MILLIS = 120L
private const val TMDB_SEARCH_DEBOUNCE_MILLIS = 450L
private const val OPENWEBIF_REFRESH_INTERVAL_MILLIS = 5L * 60L * 1_000L

@Composable
fun LauncherApp(
    installedAppsRepository: InstalledAppsRepository,
    watchNextRepository: WatchNextRepository,
    previewChannelsRepository: PreviewChannelsRepository,
    watchNextEnrichmentRepository: WatchNextEnrichmentRepository,
    searchRepository: SearchRepository,
    openWebifRepository: OpenWebifRepository,
    epgRepository: EpgRepository,
    updateManager: UpdateManager,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(LauncherSection.Home) }
    var selectedDetailsSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSearchDetailsMedia by remember { mutableStateOf<MediaItem?>(null) }
    var selectedSearchDetailsResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLiveTvServiceReference by rememberSaveable { mutableStateOf<String?>(null) }
    var watchNextFocusRestoreSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var watchNextFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var liveTvFocusRestoreServiceReference by rememberSaveable { mutableStateOf<String?>(null) }
    var liveTvFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var searchFocusRestoreResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var selectedEpgServiceReference by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEpgProgramStartUtcMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var localSearchResults by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var tmdbSearchResults by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var isTmdbSearchLoading by remember { mutableStateOf(false) }
    val watchNextListState = rememberLazyListState()
    val appsListState = rememberLazyListState()
    val liveTvListState = rememberLazyListState()
    val epgChannelListState = rememberLazyListState()
    val epgProgramListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val updateState by updateManager.state.collectAsState()
    val openWebifState by openWebifRepository.state.collectAsState()
    val epgState by epgRepository.state.collectAsState()
    var hasTvListingsPermission by remember {
        mutableStateOf(TvProviderPermissionManager.hasReadTvListings(context))
    }
    var tvProviderRefreshGeneration by remember { mutableIntStateOf(0) }
    val watchNextSourcePreferences = remember(context) { WatchNextSourcePreferences(context) }
    val hiddenWatchNextPackages by watchNextSourcePreferences.hiddenPackages.collectAsState()
    val previewChannelPreferences = remember(context) { PreviewChannelPreferences(context) }
    val hiddenPreviewChannelIds by previewChannelPreferences.hiddenChannelIds.collectAsState()

    val tvListingsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        hasTvListingsPermission = TvProviderPermissionManager.hasReadTvListings(context)
        tvProviderRefreshGeneration += 1
    }

    val requestTvListingsPermission: () -> Unit = {
        TvProviderPermissionManager.markInitialRequestShown(context)
        tvListingsPermissionLauncher.launch(TvProviderPermissionManager.READ_TV_LISTINGS)
    }

    val watchNextFlow = remember(
        watchNextRepository,
        hasTvListingsPermission,
        tvProviderRefreshGeneration,
    ) {
        watchNextRepository.observe()
    }
    val watchNextResult by watchNextFlow.collectAsState(
        initial = WatchNextLoadResult(items = emptyList()),
    )
    val visibleWatchNextItems = remember(watchNextResult.items, hiddenWatchNextPackages) {
        watchNextResult.items.filter { item ->
            val packageName = item.packageName
            packageName == null || packageName !in hiddenWatchNextPackages
        }
    }

    val previewChannelsFlow = remember(
        previewChannelsRepository,
        hasTvListingsPermission,
        tvProviderRefreshGeneration,
    ) {
        previewChannelsRepository.observe()
    }
    val previewChannelsResult by previewChannelsFlow.collectAsState(
        initial = AppContentChannelsLoadResult(channels = emptyList()),
    )
    val visiblePreviewChannels = remember(previewChannelsResult.channels, hiddenPreviewChannelIds) {
        previewChannelsResult.channels.filter { it.id !in hiddenPreviewChannelIds }
    }

    var homeWatchNextItems by remember {
        mutableStateOf<List<EnrichedWatchNextItem>>(emptyList())
    }

    LaunchedEffect(visibleWatchNextItems, watchNextEnrichmentRepository) {
        val baseItems = watchNextEnrichmentRepository.base(visibleWatchNextItems)
        homeWatchNextItems = baseItems
        if (baseItems.isNotEmpty() && watchNextEnrichmentRepository.isTmdbConfigured) {
            suspend fun enrichBatches(items: List<EnrichedWatchNextItem>) {
                items.chunked(TMDB_ENRICHMENT_BATCH_SIZE).forEach { batch ->
                    val enrichedBatch = watchNextEnrichmentRepository.enrich(batch)
                    val enrichedBySourceId = enrichedBatch.associateBy { it.media.source.sourceId }
                    homeWatchNextItems = homeWatchNextItems.map { current ->
                        enrichedBySourceId[current.media.source.sourceId] ?: current
                    }
                }
            }

            enrichBatches(baseItems)

            val unresolvedItems = homeWatchNextItems.filter { it.media.tmdbId == null }
            if (unresolvedItems.isNotEmpty()) {
                delay(TMDB_ENRICHMENT_RETRY_DELAY_MILLIS)
                enrichBatches(unresolvedItems)
            }
        }
    }

    LaunchedEffect(openWebifRepository, epgRepository) {
        launch {
            epgRepository.refresh(openWebifRepository.state.value.channels)
        }
        while (true) {
            openWebifRepository.refresh()
            epgRepository.refresh(openWebifRepository.state.value.channels)
            delay(OPENWEBIF_REFRESH_INTERVAL_MILLIS)
        }
    }

    val apps by produceState<List<InstalledApp>>(
        initialValue = emptyList(),
        key1 = installedAppsRepository,
    ) {
        value = withContext(Dispatchers.IO) {
            installedAppsRepository.loadApps()
        }
    }

    val enrichedLiveTvByRef = remember(epgState.enrichedChannels) {
        epgState.enrichedChannels.associateBy { it.serviceReference }
    }
    val displayLiveTvChannels = remember(openWebifState.channels, enrichedLiveTvByRef) {
        openWebifState.channels.map { channel ->
            enrichedLiveTvByRef[channel.serviceReference] ?: channel
        }
    }
    val displayLiveTvState = remember(openWebifState, displayLiveTvChannels) {
        openWebifState.copy(channels = displayLiveTvChannels)
    }

    LaunchedEffect(
        searchQuery,
        apps,
        homeWatchNextItems,
        visiblePreviewChannels,
        displayLiveTvChannels,
        epgState,
        searchRepository,
    ) {
        val requestedQuery = searchQuery
        if (requestedQuery.trim().length < 2) {
            localSearchResults = emptyList()
            return@LaunchedEffect
        }

        delay(LOCAL_SEARCH_DEBOUNCE_MILLIS)
        val results = withContext(Dispatchers.Default) {
            searchRepository.searchLocal(
                query = requestedQuery,
                apps = apps,
                watchNextItems = homeWatchNextItems,
                previewChannels = visiblePreviewChannels,
                liveTvChannels = displayLiveTvChannels,
                epgState = epgState,
            )
        }
        if (searchQuery == requestedQuery) {
            localSearchResults = results
        }
    }

    LaunchedEffect(searchQuery, searchRepository) {
        val requestedQuery = searchQuery.trim()
        if (requestedQuery.length < 3 || !searchRepository.isTmdbConfigured) {
            tmdbSearchResults = emptyList()
            isTmdbSearchLoading = false
            return@LaunchedEffect
        }
        delay(TMDB_SEARCH_DEBOUNCE_MILLIS)
        isTmdbSearchLoading = true
        val results = searchRepository.searchTmdb(requestedQuery)
        if (searchQuery.trim() == requestedQuery) {
            tmdbSearchResults = results
            isTmdbSearchLoading = false
        }
    }

    LaunchedEffect(displayLiveTvChannels) {
        if (
            selectedEpgServiceReference == null ||
            displayLiveTvChannels.none { it.serviceReference == selectedEpgServiceReference }
        ) {
            selectedEpgServiceReference = displayLiveTvChannels.firstOrNull()?.serviceReference
            selectedEpgProgramStartUtcMillis = null
        }
        selectedLiveTvServiceReference?.let { selectedRef ->
            if (displayLiveTvChannels.none { it.serviceReference == selectedRef }) {
                selectedLiveTvServiceReference = null
            }
        }
    }

    val selectedEpgProgram = selectedEpgServiceReference?.let { serviceReference ->
        selectedEpgProgramStartUtcMillis?.let { start ->
            epgState.guide(serviceReference).firstOrNull { it.startUtcMillis == start }
        }
    }

    val selectedDetailsItem = selectedDetailsSourceId?.let { selectedSourceId ->
        homeWatchNextItems.firstOrNull { it.media.source.sourceId == selectedSourceId }
    }
    val selectedDetailsMedia = selectedDetailsItem?.media ?: selectedSearchDetailsMedia
    val closeDetails: () -> Unit = {
        if (selectedDetailsItem != null) {
            selectedDetailsSourceId?.let { sourceId ->
                watchNextFocusRestoreSourceId = sourceId
                watchNextFocusRestoreGeneration += 1
            }
            selectedDetailsSourceId = null
        } else {
            selectedSearchDetailsResultId?.let { resultId ->
                searchFocusRestoreResultId = resultId
                searchFocusRestoreGeneration += 1
            }
            selectedSearchDetailsResultId = null
            selectedSearchDetailsMedia = null
        }
    }
    val closeLiveTvPlayer: () -> Unit = {
        selectedLiveTvServiceReference?.let { serviceReference ->
            liveTvFocusRestoreServiceReference = serviceReference
            liveTvFocusRestoreGeneration += 1
        }
        selectedLiveTvServiceReference = null
    }
    BackHandler(enabled = selectedDetailsMedia != null, onBack = closeDetails)

    DisposableEffect(activity) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val granted = TvProviderPermissionManager.hasReadTvListings(context)
                    if (granted != hasTvListingsPermission) {
                        hasTvListingsPermission = granted
                        tvProviderRefreshGeneration += 1
                    }
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(Unit) {
        if (TvProviderPermissionManager.shouldShowInitialRequest(context)) {
            TvProviderPermissionManager.markInitialRequestShown(context)
            tvListingsPermissionLauncher.launch(TvProviderPermissionManager.READ_TV_LISTINGS)
        }
    }

    LaunchedEffect(updateManager) {
        updateManager.checkForUpdates()
    }

    val openApp: (InstalledApp) -> Unit = { app -> installedAppsRepository.launch(app) }
    val openWatchNext: (EnrichedWatchNextItem) -> Unit = { item ->
        watchNextRepository.launch(item.sourceItem)
    }
    val openSearchResult: (SearchItem) -> Unit = { result ->
        when (result.kind) {
            SearchResultKind.App -> {
                result.packageName
                    ?.let { packageName -> apps.firstOrNull { it.packageName == packageName } }
                    ?.let(openApp)
            }

            SearchResultKind.WatchNext -> {
                val sourceId = result.media?.source?.sourceId
                sourceId
                    ?.let { id -> homeWatchNextItems.firstOrNull { it.media.source.sourceId == id } }
                    ?.let(openWatchNext)
            }

            SearchResultKind.PreviewProgram -> {
                val sourceId = result.media?.source?.sourceId
                val channel = visiblePreviewChannels.firstOrNull { it.id == result.previewChannelId }
                val program = channel?.programs?.firstOrNull { it.media.source.sourceId == sourceId }
                if (program != null) previewChannelsRepository.launch(program)
            }

            SearchResultKind.EpgProgram -> {
                val serviceReference = result.serviceReference
                val startUtcMillis = result.programStartUtcMillis
                if (serviceReference != null && startUtcMillis != null) {
                    selectedEpgServiceReference = serviceReference
                    selectedEpgProgramStartUtcMillis = startUtcMillis
                    section = LauncherSection.Epg
                    scope.launch {
                        epgRepository.enrichProgram(serviceReference, startUtcMillis)
                    }
                }
            }

            SearchResultKind.Tmdb -> {
                result.media?.let { media ->
                    selectedDetailsSourceId = null
                    selectedSearchDetailsResultId = result.id
                    selectedSearchDetailsMedia = media
                    scope.launch {
                        val detailed = searchRepository.loadTmdbDetails(media)
                        if (selectedSearchDetailsResultId == result.id) {
                            selectedSearchDetailsMedia = detailed
                        }
                    }
                }
            }
        }
    }
    val updateAttentionLabel = when (updateState) {
        is UpdateState.Available,
        is UpdateState.ReadyToInstall,
        -> "Update verfügbar"

        is UpdateState.SigningRequired -> "Update-Setup nötig"
        else -> null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        when {
            selectedLiveTvServiceReference != null && displayLiveTvChannels.isNotEmpty() -> {
                LiveTvPlayerScreen(
                    channels = displayLiveTvChannels,
                    initialServiceReference = requireNotNull(selectedLiveTvServiceReference),
                    onResolveStream = openWebifRepository::resolveStream,
                    onBack = closeLiveTvPlayer,
                )
            }

            selectedDetailsMedia != null -> {
                val detailsMedia = selectedDetailsMedia
                val packageName = detailsMedia.source.packageName
                val sourceLabel = if (selectedDetailsItem != null) {
                    apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
                } else {
                    "TMDB"
                }
                DetailsScreen(
                    item = detailsMedia,
                    sourceLabel = sourceLabel,
                    onPlay = selectedDetailsItem?.let { item -> { openWatchNext(item) } },
                    onBack = closeDetails,
                    onTrailer = detailsMedia.trailer?.let {
                        {
                            YouTubeLauncher.playTrailer(context, detailsMedia)
                        }
                    },
                    onTrailerSearch = if (detailsMedia.trailer == null) {
                        {
                            YouTubeLauncher.searchTrailer(context, detailsMedia)
                        }
                    } else {
                        null
                    },
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 56.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "I Launcher",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                        )
                        LauncherSection.entries.forEach { item ->
                            Button(onClick = { section = item }) {
                                Text(
                                    if (section == item) {
                                        "● ${item.label}"
                                    } else {
                                        item.label
                                    },
                                )
                            }
                        }

                        updateAttentionLabel?.let { label ->
                            Button(onClick = { section = LauncherSection.Settings }) {
                                Text(label)
                            }
                        }
                    }

                    when (section) {
                        LauncherSection.Home -> HomeScreen(
                            apps = apps,
                            watchNextItems = homeWatchNextItems,
                            watchNextError = watchNextResult.errorMessage,
                            previewChannels = visiblePreviewChannels,
                            previewChannelsError = previewChannelsResult.errorMessage,
                            hasTvListingsPermission = hasTvListingsPermission,
                            liveTvState = displayLiveTvState,
                            onRequestTvListingsPermission = requestTvListingsPermission,
                            onOpenApp = openApp,
                            onOpenWatchNext = openWatchNext,
                            onOpenWatchNextDetails = { item ->
                                liveTvFocusRestoreServiceReference = null
                                selectedSearchDetailsMedia = null
                                selectedSearchDetailsResultId = null
                                selectedDetailsSourceId = item.media.source.sourceId
                            },
                            onOpenPreviewProgram = { _, program ->
                                previewChannelsRepository.launch(program)
                            },
                            onOpenLiveTv = { section = LauncherSection.LiveTv },
                            onPlayLiveTvChannel = { channel ->
                                watchNextFocusRestoreSourceId = null
                                selectedLiveTvServiceReference = channel.serviceReference
                            },
                            watchNextListState = watchNextListState,
                            liveTvListState = liveTvListState,
                            appsListState = appsListState,
                            watchNextFocusRestoreSourceId = watchNextFocusRestoreSourceId,
                            watchNextFocusRestoreGeneration = watchNextFocusRestoreGeneration,
                            liveTvFocusRestoreServiceReference = liveTvFocusRestoreServiceReference,
                            liveTvFocusRestoreGeneration = liveTvFocusRestoreGeneration,
                        )

                        LauncherSection.Search -> SearchScreen(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            localResults = localSearchResults,
                            tmdbResults = tmdbSearchResults,
                            isTmdbLoading = isTmdbSearchLoading,
                            tmdbConfigured = searchRepository.isTmdbConfigured,
                            apps = apps,
                            onOpenResult = openSearchResult,
                            listState = searchListState,
                            focusRestoreResultId = searchFocusRestoreResultId,
                            focusRestoreGeneration = searchFocusRestoreGeneration,
                        )

                        LauncherSection.LiveTv -> LiveTvScreen(
                            state = displayLiveTvState,
                            epgState = epgState,
                            onSaveConnection = { baseUrl, username, password ->
                                if (openWebifRepository.updateConnection(baseUrl, username, password)) {
                                    scope.launch {
                                        openWebifRepository.refresh()
                                        epgRepository.refresh(openWebifRepository.state.value.channels)
                                    }
                                }
                            },
                            onSelectBouquet = { serviceReference ->
                                openWebifRepository.selectBouquet(serviceReference)
                                scope.launch {
                                    openWebifRepository.refresh()
                                    epgRepository.refresh(openWebifRepository.state.value.channels)
                                }
                            },
                            onRefresh = {
                                scope.launch {
                                    openWebifRepository.refresh()
                                    epgRepository.refresh(openWebifRepository.state.value.channels)
                                }
                            },
                            onSaveEpgSource = { sourceUrl ->
                                scope.launch {
                                    if (epgRepository.updateSource(sourceUrl)) {
                                        epgRepository.refresh(
                                            channels = openWebifRepository.state.value.channels,
                                            force = true,
                                        )
                                    }
                                }
                            },
                            onRefreshEpg = {
                                scope.launch {
                                    epgRepository.refresh(
                                        channels = openWebifRepository.state.value.channels,
                                        force = true,
                                    )
                                }
                            },
                            onSetEpgMapping = { serviceReference, xmltvChannelId ->
                                scope.launch {
                                    epgRepository.setManualMapping(serviceReference, xmltvChannelId)
                                    epgRepository.refresh(
                                        channels = openWebifRepository.state.value.channels,
                                        force = true,
                                    )
                                }
                            },
                        )

                        LauncherSection.Epg -> EpgScreen(
                            state = epgState,
                            channels = displayLiveTvChannels,
                            selectedServiceReference = selectedEpgServiceReference,
                            selectedProgram = selectedEpgProgram,
                            onSelectChannel = { serviceReference ->
                                selectedEpgServiceReference = serviceReference
                                selectedEpgProgramStartUtcMillis = null
                            },
                            onSelectProgram = { serviceReference, program ->
                                selectedEpgServiceReference = serviceReference
                                selectedEpgProgramStartUtcMillis = program.startUtcMillis
                                scope.launch {
                                    epgRepository.enrichProgram(serviceReference, program.startUtcMillis)
                                }
                            },
                            onRefresh = {
                                scope.launch {
                                    epgRepository.refresh(
                                        channels = openWebifRepository.state.value.channels,
                                        force = true,
                                    )
                                }
                            },
                            channelListState = epgChannelListState,
                            programListState = epgProgramListState,
                        )

                        LauncherSection.Apps -> AppsScreen(
                            apps = apps,
                            onOpenApp = openApp,
                        )

                        LauncherSection.Settings -> SettingsScreen(
                            updateManager = updateManager,
                            watchNextResult = watchNextResult,
                            previewChannelsResult = previewChannelsResult,
                            installedApps = apps,
                            hiddenWatchNextPackages = hiddenWatchNextPackages,
                            onSetWatchNextSourceVisible = watchNextSourcePreferences::setVisible,
                            onShowAllWatchNextSources = watchNextSourcePreferences::showAll,
                            hiddenPreviewChannelIds = hiddenPreviewChannelIds,
                            onSetPreviewChannelVisible = previewChannelPreferences::setVisible,
                            onShowAllPreviewChannels = previewChannelPreferences::showAll,
                            hasTvListingsPermission = hasTvListingsPermission,
                            onRequestTvListingsPermission = requestTvListingsPermission,
                            tmdbConfigured = watchNextEnrichmentRepository.isTmdbConfigured,
                            enrichedWatchNextItems = homeWatchNextItems,
                        )
                    }
                }
            }
        }
    }
}
