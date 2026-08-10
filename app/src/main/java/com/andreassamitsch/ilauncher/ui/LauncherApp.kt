package com.andreassamitsch.ilauncher.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.apps.InstalledAppsRepository
import com.andreassamitsch.ilauncher.data.epg.EpgRepository
import com.andreassamitsch.ilauncher.data.home.HomePreferences
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifRepository
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
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
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentChannelsLoadResult
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind
import com.andreassamitsch.ilauncher.model.WatchNextLoadResult
import com.andreassamitsch.ilauncher.system.TvProviderPermissionManager
import com.andreassamitsch.ilauncher.ui.apps.AppsScreen
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.details.DetailsScreen
import com.andreassamitsch.ilauncher.ui.home.HomeRowOption
import com.andreassamitsch.ilauncher.ui.home.HomeScreen
import com.andreassamitsch.ilauncher.ui.home.HomeSettingsScreen
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
    Apps("Apps"),
    Settings("Einstellungen"),
    LiveTv("Live TV"),
}

private val PRIMARY_SECTIONS = listOf(
    LauncherSection.Home,
    LauncherSection.Search,
    LauncherSection.Apps,
    LauncherSection.Settings,
)

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
    var navigationVisible by rememberSaveable { mutableStateOf(true) }
    var showHomeSettings by rememberSaveable { mutableStateOf(false) }
    var selectedDetailsSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSearchDetailsMedia by remember { mutableStateOf<MediaItem?>(null) }
    var selectedSearchDetailsResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHomeDetailsMedia by remember { mutableStateOf<MediaItem?>(null) }
    var selectedHomeDetailsSourceLabel by remember { mutableStateOf<String?>(null) }
    var selectedLiveTvServiceReference by rememberSaveable { mutableStateOf<String?>(null) }
    var openPlayerEpgInitially by rememberSaveable { mutableStateOf(false) }
    var initialPlayerEpgProgramStartUtcMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var watchNextFocusRestoreSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var watchNextFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var liveTvFocusRestoreServiceReference by rememberSaveable { mutableStateOf<String?>(null) }
    var liveTvFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var searchFocusRestoreResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var localSearchResults by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var tmdbSearchResults by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var browseSections by remember { mutableStateOf<List<SearchBrowseSection>>(emptyList()) }
    var isTmdbSearchLoading by remember { mutableStateOf(false) }
    var isBrowseLoading by remember { mutableStateOf(false) }
    val watchNextListState = rememberLazyListState()
    val appsListState = rememberLazyListState()
    val liveTvListState = rememberLazyListState()
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
    val homePreferences = remember(context) { HomePreferences(context) }
    val savedHomeRowOrder by homePreferences.rowOrder.collectAsState()
    val savedHomeAppOrder by homePreferences.appOrder.collectAsState()

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

    val watchNextFlow = remember(watchNextRepository, hasTvListingsPermission, tvProviderRefreshGeneration) {
        watchNextRepository.observe()
    }
    val watchNextResult by watchNextFlow.collectAsState(initial = WatchNextLoadResult(items = emptyList()))
    val visibleWatchNextItems = remember(watchNextResult.items, hiddenWatchNextPackages) {
        watchNextResult.items.filter { item ->
            val packageName = item.packageName
            packageName == null || packageName !in hiddenWatchNextPackages
        }
    }

    val previewChannelsFlow = remember(previewChannelsRepository, hasTvListingsPermission, tvProviderRefreshGeneration) {
        previewChannelsRepository.observe()
    }
    val previewChannelsResult by previewChannelsFlow.collectAsState(
        initial = AppContentChannelsLoadResult(channels = emptyList()),
    )
    val visiblePreviewChannels = remember(previewChannelsResult.channels, hiddenPreviewChannelIds) {
        previewChannelsResult.channels.filter { it.id !in hiddenPreviewChannelIds }
    }

    var homeWatchNextItems by remember { mutableStateOf<List<EnrichedWatchNextItem>>(emptyList()) }
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
        launch { epgRepository.refresh(openWebifRepository.state.value.channels) }
        while (true) {
            openWebifRepository.refresh()
            epgRepository.refresh(openWebifRepository.state.value.channels)
            delay(OPENWEBIF_REFRESH_INTERVAL_MILLIS)
        }
    }

    val apps by produceState<List<InstalledApp>>(initialValue = emptyList(), key1 = installedAppsRepository) {
        value = withContext(Dispatchers.IO) { installedAppsRepository.loadApps() }
    }
    val orderedHomeApps = remember(apps, savedHomeAppOrder) {
        HomePreferences.orderApps(apps, savedHomeAppOrder)
    }
    val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }

    val enrichedLiveTvByRef = remember(epgState.enrichedChannels) {
        epgState.enrichedChannels.associateBy { it.serviceReference }
    }
    val displayLiveTvChannels = remember(openWebifState.channels, enrichedLiveTvByRef) {
        openWebifState.channels.map { channel -> enrichedLiveTvByRef[channel.serviceReference] ?: channel }
    }
    val displayLiveTvState = remember(openWebifState, displayLiveTvChannels) {
        openWebifState.copy(channels = displayLiveTvChannels)
    }

    val availableHomeRowKeys = remember(displayLiveTvState.configured, visiblePreviewChannels, orderedHomeApps) {
        buildList {
            add(HomePreferences.ROW_WATCH_NEXT)
            if (displayLiveTvState.configured) add(HomePreferences.ROW_LIVE_TV)
            visiblePreviewChannels.filter { it.programs.isNotEmpty() }.forEach {
                add(HomePreferences.previewRowKey(it.id))
            }
            if (orderedHomeApps.isNotEmpty()) add(HomePreferences.ROW_APPS)
        }
    }
    val homeRowOrder = remember(savedHomeRowOrder, availableHomeRowKeys) {
        HomePreferences.mergeOrder(savedHomeRowOrder, availableHomeRowKeys)
    }
    val homeRowOptions = remember(homeRowOrder, visiblePreviewChannels) {
        val previewTitles = visiblePreviewChannels.associate { HomePreferences.previewRowKey(it.id) to it.title }
        homeRowOrder.map { key ->
            HomeRowOption(
                key = key,
                title = when (key) {
                    HomePreferences.ROW_WATCH_NEXT -> "Weiterschauen"
                    HomePreferences.ROW_LIVE_TV -> "Jetzt im TV"
                    HomePreferences.ROW_APPS -> "Apps"
                    else -> previewTitles[key] ?: "App-Kanal"
                },
            )
        }
    }

    LaunchedEffect(searchQuery, apps, homeWatchNextItems, visiblePreviewChannels, displayLiveTvChannels, epgState, searchRepository) {
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
        if (searchQuery == requestedQuery) localSearchResults = results
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

    LaunchedEffect(section, searchQuery, searchRepository) {
        if (section != LauncherSection.Search || searchQuery.isNotBlank() || !searchRepository.isTmdbConfigured) {
            return@LaunchedEffect
        }
        isBrowseLoading = true
        browseSections = searchRepository.browseTmdb()
        isBrowseLoading = false
    }

    val sourceLinkedTmdbResults = remember(tmdbSearchResults, homeWatchNextItems, visiblePreviewChannels, appLabels) {
        tmdbSearchResults.map { result ->
            linkTmdbResultToLocalSource(result, homeWatchNextItems, visiblePreviewChannels, appLabels)
        }
    }
    val mergedLocalSearchResults = remember(localSearchResults, sourceLinkedTmdbResults) {
        (localSearchResults + sourceLinkedTmdbResults.filter { it.kind != SearchResultKind.Tmdb })
            .distinctBy(SearchItem::id)
    }
    val pureTmdbSearchResults = remember(sourceLinkedTmdbResults, mergedLocalSearchResults) {
        val localTmdbKeys = mergedLocalSearchResults.mapNotNull { it.media?.tmdbIdentityKey() }.toSet()
        sourceLinkedTmdbResults
            .filter { it.kind == SearchResultKind.Tmdb }
            .filter { it.media?.tmdbIdentityKey() !in localTmdbKeys }
    }

    LaunchedEffect(displayLiveTvChannels) {
        selectedLiveTvServiceReference?.let { selectedRef ->
            if (displayLiveTvChannels.none { it.serviceReference == selectedRef }) {
                selectedLiveTvServiceReference = null
                openPlayerEpgInitially = false
                initialPlayerEpgProgramStartUtcMillis = null
            }
        }
    }
    LaunchedEffect(section) { if (section != LauncherSection.Home) navigationVisible = true }

    val selectedDetailsItem = selectedDetailsSourceId?.let { selectedSourceId ->
        homeWatchNextItems.firstOrNull { it.media.source.sourceId == selectedSourceId }
    }
    val selectedDetailsMedia = selectedDetailsItem?.media ?: selectedSearchDetailsMedia ?: selectedHomeDetailsMedia

    val closeDetails: () -> Unit = {
        when {
            selectedSearchDetailsResultId != null -> {
                selectedSearchDetailsResultId?.let { resultId ->
                    searchFocusRestoreResultId = resultId
                    searchFocusRestoreGeneration += 1
                }
                selectedSearchDetailsResultId = null
                selectedSearchDetailsMedia = null
                selectedDetailsSourceId = null
            }
            selectedDetailsItem != null -> {
                selectedDetailsSourceId?.let { sourceId ->
                    watchNextFocusRestoreSourceId = sourceId
                    watchNextFocusRestoreGeneration += 1
                }
                selectedDetailsSourceId = null
            }
            else -> {
                selectedHomeDetailsMedia = null
                selectedHomeDetailsSourceLabel = null
            }
        }
    }
    val closeLiveTvPlayer: () -> Unit = {
        selectedLiveTvServiceReference?.let { serviceReference ->
            if (section == LauncherSection.Home) {
                liveTvFocusRestoreServiceReference = serviceReference
                liveTvFocusRestoreGeneration += 1
            }
        }
        selectedLiveTvServiceReference = null
        openPlayerEpgInitially = false
        initialPlayerEpgProgramStartUtcMillis = null
    }

    BackHandler(enabled = selectedDetailsMedia != null, onBack = closeDetails)
    BackHandler(enabled = showHomeSettings && selectedDetailsMedia == null && selectedLiveTvServiceReference == null) {
        showHomeSettings = false
        section = LauncherSection.Home
        navigationVisible = true
    }
    BackHandler(
        enabled = !showHomeSettings && selectedDetailsMedia == null && selectedLiveTvServiceReference == null && section == LauncherSection.LiveTv,
    ) { section = LauncherSection.Settings }

    DisposableEffect(activity) {
        if (activity == null) onDispose { } else {
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
    LaunchedEffect(updateManager) { updateManager.checkForUpdates() }

    val openApp: (InstalledApp) -> Unit = { app -> installedAppsRepository.launch(app) }
    val openWatchNext: (EnrichedWatchNextItem) -> Unit = { item -> watchNextRepository.launch(item.sourceItem) }
    val openSearchResult: (SearchItem) -> Unit = { result ->
        when (result.kind) {
            SearchResultKind.App -> result.packageName
                ?.let { packageName -> apps.firstOrNull { it.packageName == packageName } }
                ?.let(openApp)

            SearchResultKind.WatchNext -> {
                val sourceId = result.media?.source?.sourceId
                val item = sourceId?.let { id -> homeWatchNextItems.firstOrNull { it.media.source.sourceId == id } }
                if (item != null) {
                    selectedHomeDetailsMedia = null
                    selectedHomeDetailsSourceLabel = null
                    selectedSearchDetailsMedia = null
                    selectedSearchDetailsResultId = result.id
                    selectedDetailsSourceId = item.media.source.sourceId
                }
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
                    selectedLiveTvServiceReference = serviceReference
                    openPlayerEpgInitially = true
                    initialPlayerEpgProgramStartUtcMillis = startUtcMillis
                    scope.launch { epgRepository.enrichProgram(serviceReference, startUtcMillis) }
                }
            }

            SearchResultKind.Tmdb -> result.media?.let { media ->
                selectedDetailsSourceId = null
                selectedHomeDetailsMedia = null
                selectedSearchDetailsResultId = result.id
                selectedSearchDetailsMedia = media
                scope.launch {
                    val detailed = searchRepository.loadTmdbDetails(media)
                    if (selectedSearchDetailsResultId == result.id) selectedSearchDetailsMedia = detailed
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        when {
            selectedDetailsMedia != null -> {
                val detailsMedia = selectedDetailsMedia
                val packageName = detailsMedia.source.packageName
                val sourceLabel = when {
                    selectedDetailsItem != null -> apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
                    selectedSearchDetailsResultId != null -> "TMDB"
                    else -> selectedHomeDetailsSourceLabel
                }
                DetailsScreen(
                    item = detailsMedia,
                    sourceLabel = sourceLabel,
                    onPlay = selectedDetailsItem?.let { item -> { openWatchNext(item) } },
                    onBack = closeDetails,
                    onTrailer = detailsMedia.trailer?.let { { YouTubeLauncher.playTrailer(context, detailsMedia) } },
                    onTrailerSearch = if (detailsMedia.trailer == null) {
                        { YouTubeLauncher.searchTrailer(context, detailsMedia) }
                    } else null,
                )
            }

            selectedLiveTvServiceReference != null && displayLiveTvChannels.isNotEmpty() -> LiveTvPlayerScreen(
                channels = displayLiveTvChannels,
                initialServiceReference = requireNotNull(selectedLiveTvServiceReference),
                onResolveStream = openWebifRepository::resolveStream,
                epgState = epgState,
                initialShowEpg = openPlayerEpgInitially,
                initialEpgProgramStartUtcMillis = initialPlayerEpgProgramStartUtcMillis,
                onRefreshEpg = {
                    scope.launch { epgRepository.refresh(openWebifRepository.state.value.channels, force = true) }
                },
                onEnrichEpgProgram = { serviceReference, startUtcMillis ->
                    scope.launch { epgRepository.enrichProgram(serviceReference, startUtcMillis) }
                },
                onOpenEpgProgramDetails = { channel, program ->
                    selectedDetailsSourceId = null
                    selectedSearchDetailsMedia = null
                    selectedSearchDetailsResultId = null
                    selectedHomeDetailsMedia = epgProgramMedia(channel, program)
                    selectedHomeDetailsSourceLabel = channel.name
                    openPlayerEpgInitially = true
                    initialPlayerEpgProgramStartUtcMillis = program.startUtcMillis
                },
                onBack = closeLiveTvPlayer,
            )

            showHomeSettings -> HomeSettingsScreen(
                rowOptions = homeRowOptions,
                onMoveRow = { key, delta -> homePreferences.moveRow(availableHomeRowKeys, key, delta) },
                onResetRows = homePreferences::resetRows,
                onResetApps = homePreferences::resetApps,
                watchNextResult = watchNextResult,
                previewChannelsResult = previewChannelsResult,
                installedApps = apps,
                hiddenWatchNextPackages = hiddenWatchNextPackages,
                onSetWatchNextSourceVisible = watchNextSourcePreferences::setVisible,
                onShowAllWatchNextSources = watchNextSourcePreferences::showAll,
                hiddenPreviewChannelIds = hiddenPreviewChannelIds,
                onSetPreviewChannelVisible = previewChannelPreferences::setVisible,
                onShowAllPreviewChannels = previewChannelPreferences::showAll,
                onBack = {
                    showHomeSettings = false
                    navigationVisible = true
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (navigationVisible) 5.dp else 0.dp),
            ) {
                if (navigationVisible) {
                    val activePrimarySection = if (section == LauncherSection.LiveTv) LauncherSection.Settings else section
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PRIMARY_SECTIONS.forEach { item ->
                            val active = activePrimarySection == item
                            val navColors = ButtonDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = if (active) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                                },
                                focusedContainerColor = MaterialTheme.colorScheme.onSurface,
                                focusedContentColor = MaterialTheme.colorScheme.surface,
                                pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                                pressedContentColor = MaterialTheme.colorScheme.surface,
                                disabledContainerColor = Color.Transparent,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            )
                            TouchButton(
                                onClick = {
                                    showHomeSettings = false
                                    section = item
                                },
                                onLongClick = if (item == LauncherSection.Home) {
                                    {
                                        section = LauncherSection.Home
                                        navigationVisible = true
                                        showHomeSettings = true
                                    }
                                } else null,
                                scale = ButtonDefaults.scale(
                                    focusedScale = 1.025f,
                                    pressedScale = 0.985f,
                                ),
                                colors = navColors,
                                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .height(40.dp)
                                    .then(
                                        if (active) {
                                            Modifier.border(
                                                width = 1.5.dp,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                                                shape = RoundedCornerShape(50),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }

                when (section) {
                    LauncherSection.Home -> HomeScreen(
                        apps = orderedHomeApps,
                        watchNextItems = homeWatchNextItems,
                        watchNextError = watchNextResult.errorMessage,
                        previewChannels = visiblePreviewChannels,
                        previewChannelsError = previewChannelsResult.errorMessage,
                        hasTvListingsPermission = hasTvListingsPermission,
                        liveTvState = displayLiveTvState,
                        homeRowOrder = homeRowOrder,
                        onMoveHomeApp = { packageName, delta ->
                            homePreferences.moveApp(apps.map(InstalledApp::packageName), packageName, delta)
                        },
                        onRequestTvListingsPermission = requestTvListingsPermission,
                        onOpenApp = openApp,
                        onOpenWatchNext = openWatchNext,
                        onOpenWatchNextDetails = { item ->
                            liveTvFocusRestoreServiceReference = null
                            selectedSearchDetailsMedia = null
                            selectedSearchDetailsResultId = null
                            selectedHomeDetailsMedia = null
                            selectedDetailsSourceId = item.media.source.sourceId
                        },
                        onOpenMediaDetails = { media, label ->
                            selectedDetailsSourceId = null
                            selectedSearchDetailsMedia = null
                            selectedSearchDetailsResultId = null
                            selectedHomeDetailsMedia = media
                            selectedHomeDetailsSourceLabel = label
                        },
                        onOpenPreviewProgram = { _, program -> previewChannelsRepository.launch(program) },
                        onOpenLiveTv = { section = LauncherSection.LiveTv },
                        onPlayLiveTvChannel = { channel ->
                            watchNextFocusRestoreSourceId = null
                            openPlayerEpgInitially = false
                            initialPlayerEpgProgramStartUtcMillis = null
                            selectedLiveTvServiceReference = channel.serviceReference
                        },
                        onNavigationVisibilityChange = { navigationVisible = it },
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
                        localResults = mergedLocalSearchResults,
                        tmdbResults = pureTmdbSearchResults,
                        browseSections = browseSections,
                        isTmdbLoading = isTmdbSearchLoading,
                        isBrowseLoading = isBrowseLoading,
                        tmdbConfigured = searchRepository.isTmdbConfigured,
                        apps = apps,
                        onOpenResult = openSearchResult,
                        listState = searchListState,
                        focusRestoreResultId = searchFocusRestoreResultId,
                        focusRestoreGeneration = searchFocusRestoreGeneration,
                    )

                    LauncherSection.Apps -> AppsScreen(apps = apps, onOpenApp = openApp)

                    LauncherSection.Settings -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TouchButton(
                            onClick = {
                                when (val state = updateState) {
                                    is UpdateState.Available -> updateManager.startDownload(state.info)
                                    is UpdateState.ReadyToInstall -> scope.launch {
                                        if (!updateManager.canRequestPackageInstalls()) updateManager.openUnknownSourcesSettings()
                                        else updateManager.installDownloadedUpdate()
                                    }
                                    else -> scope.launch { updateManager.checkForUpdates() }
                                }
                            },
                            enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
                        ) {
                            Text(
                                when (val state = updateState) {
                                    is UpdateState.Available -> "Update ${state.info.versionName} herunterladen"
                                    is UpdateState.ReadyToInstall -> "Update ${state.info.versionName} installieren"
                                    is UpdateState.Downloading -> "Update wird heruntergeladen …"
                                    UpdateState.Checking -> "Suche nach Update …"
                                    else -> "Nach Update suchen"
                                },
                            )
                        }
                        TouchButton(onClick = { section = LauncherSection.LiveTv }) { Text("Live TV / Gigablue") }
                        SettingsScreen(
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
                            modifier = Modifier.weight(1f),
                        )
                    }

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
                                    epgRepository.refresh(openWebifRepository.state.value.channels, force = true)
                                }
                            }
                        },
                        onRefreshEpg = {
                            scope.launch { epgRepository.refresh(openWebifRepository.state.value.channels, force = true) }
                        },
                        onSetEpgMapping = { serviceReference, xmltvChannelId ->
                            scope.launch {
                                epgRepository.setManualMapping(serviceReference, xmltvChannelId)
                                epgRepository.refresh(openWebifRepository.state.value.channels, force = true)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun epgProgramMedia(channel: LiveTvChannel, program: LiveTvProgram): MediaItem = MediaItem(
    id = "epg:${channel.serviceReference}:${program.startUtcMillis}",
    type = program.tmdbType ?: MediaType.Unknown,
    title = program.title,
    subtitle = program.subtitle,
    overview = program.longDescription ?: program.shortDescription,
    releaseYear = program.releaseYear,
    tmdbId = program.tmdbId,
    tmdbEpisodeId = program.tmdbEpisodeId,
    seasonNumber = program.seasonNumber,
    episodeNumber = program.episodeNumber,
    posterUri = program.posterUri,
    backdropUri = program.backdropUri,
    episodeStillUri = program.episodeStillUri,
    sourceArtworkUri = program.imageUri,
    voteAverage = program.voteAverage,
    source = MediaSource(
        provider = "epg",
        sourceId = "${channel.serviceReference}:${program.startUtcMillis}",
    ),
)

private fun linkTmdbResultToLocalSource(
    result: SearchItem,
    watchNextItems: List<EnrichedWatchNextItem>,
    previewChannels: List<AppContentChannel>,
    appLabels: Map<String, String>,
): SearchItem {
    val media = result.media ?: return result
    val identityKey = media.tmdbIdentityKey() ?: return result

    watchNextItems.firstOrNull { it.media.tmdbIdentityKey() == identityKey }?.let { item ->
        val sourceMedia = item.media
        val packageName = sourceMedia.source.packageName
        return SearchItem(
            id = "search:watch:${sourceMedia.source.sourceId}",
            kind = SearchResultKind.WatchNext,
            title = sourceMedia.title,
            subtitle = sourceMedia.subtitle,
            artworkUri = sourceMedia.preferredArtworkUri ?: result.artworkUri,
            sourceLabel = packageName?.let { appLabels[it] ?: it },
            media = sourceMedia,
            packageName = packageName,
        )
    }

    previewChannels.forEach { channel ->
        channel.programs.firstOrNull { it.media.tmdbIdentityKey() == identityKey }?.let { program ->
            val packageName = channel.packageName ?: program.media.source.packageName
            val sourceLabel = packageName?.let { appLabels[it] ?: it }
            return SearchItem(
                id = "search:preview:${channel.id}:${program.media.source.sourceId}",
                kind = SearchResultKind.PreviewProgram,
                title = program.media.title,
                subtitle = program.media.subtitle,
                artworkUri = program.media.preferredArtworkUri ?: result.artworkUri,
                sourceLabel = listOfNotNull(channel.title, sourceLabel).distinct().joinToString(" · "),
                media = program.media,
                packageName = packageName,
                previewChannelId = channel.id,
            )
        }
    }

    return result
}

private fun MediaItem.tmdbIdentityKey(): String? {
    val id = tmdbId ?: return null
    val typeKey = when (type) {
        MediaType.Movie -> "movie"
        MediaType.Series, MediaType.Episode -> "tv"
        MediaType.Unknown -> return null
    }
    return "$typeKey:$id"
}
