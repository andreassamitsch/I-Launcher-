package com.andreassamitsch.ilauncher.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.andreassamitsch.ilauncher.data.tmdb.TmdbDiscoveryPreferences
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
import com.andreassamitsch.ilauncher.ui.discover.ContentDiscoveryScreen
import com.andreassamitsch.ilauncher.ui.discover.ContentDiscoverySettingsScreen
import com.andreassamitsch.ilauncher.ui.home.HomeRowOption
import com.andreassamitsch.ilauncher.ui.home.HomeScreen
import com.andreassamitsch.ilauncher.ui.home.HomeSettingsScreen
import com.andreassamitsch.ilauncher.ui.livetv.LiveTvPlayerScreen
import com.andreassamitsch.ilauncher.ui.livetv.LiveTvScreen
import com.andreassamitsch.ilauncher.ui.search.SearchScreen
import com.andreassamitsch.ilauncher.ui.settings.SettingsCategory
import com.andreassamitsch.ilauncher.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LauncherSection(val label: String) {
    Home("Home"),
    Movies("Filme"),
    Series("Serien"),
    Search("Suche"),
    Apps("Apps"),
    Settings("Einstellungen"),
    LiveTv("Live TV"),
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
    homeNavigationRequestGeneration: Int = 0,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(LauncherSection.Home) }
    var showHomeSettings by rememberSaveable { mutableStateOf(false) }
    var discoverySettingsSection by rememberSaveable { mutableStateOf<LauncherSection?>(null) }
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
    var homeHeroFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var restoreHomeHeroOnDetailsClose by rememberSaveable { mutableStateOf(false) }
    var homeAppsFocusRestoreKey by rememberSaveable { mutableStateOf<String?>(null) }
    var homeAppsFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var homeLiveTvConfigFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var liveTvConfigurationReturnSection by rememberSaveable { mutableStateOf(LauncherSection.Settings) }
    var liveTvPlayerSearchResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsSelectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.Setup) }
    var settingsLiveTvActionFocusRestoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var localSearchResults by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var tmdbSearchResults by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var movieBrowseSections by remember { mutableStateOf<List<SearchBrowseSection>>(emptyList()) }
    var seriesBrowseSections by remember { mutableStateOf<List<SearchBrowseSection>>(emptyList()) }
    var isTmdbSearchLoading by remember { mutableStateOf(false) }
    var isMovieBrowseLoading by remember { mutableStateOf(false) }
    var isSeriesBrowseLoading by remember { mutableStateOf(false) }
    val watchNextListState = rememberLazyListState()
    val appsListState = rememberLazyListState()
    val liveTvListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val movieBrowseListState = rememberLazyListState()
    val seriesBrowseListState = rememberLazyListState()
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
    val tmdbEnrichedPreviewChannelIds by previewChannelPreferences.tmdbEnrichedChannelIds.collectAsState()
    val homePreferences = remember(context) { HomePreferences(context) }
    val savedHomeRowOrder by homePreferences.rowOrder.collectAsState()
    val savedHomeAppOrder by homePreferences.appOrder.collectAsState()
    val watchNextCardArtworkMode by homePreferences.watchNextCardArtworkMode.collectAsState()
    val watchNextHeroArtworkMode by homePreferences.watchNextHeroArtworkMode.collectAsState()
    val heroTextScrollSpeed by homePreferences.heroTextScrollSpeed.collectAsState()
    val tmdbDiscoveryPreferences = remember(context) { TmdbDiscoveryPreferences(context) }
    val movieDiscoveryRowKeys by tmdbDiscoveryPreferences.movieRowKeys.collectAsState()
    val seriesDiscoveryRowKeys by tmdbDiscoveryPreferences.seriesRowKeys.collectAsState()

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
    val baseVisiblePreviewChannels = remember(previewChannelsResult.channels, hiddenPreviewChannelIds) {
        previewChannelsResult.channels.filter { it.id !in hiddenPreviewChannelIds }
    }
    var visiblePreviewChannels by remember { mutableStateOf<List<AppContentChannel>>(emptyList()) }

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

    LaunchedEffect(
        baseVisiblePreviewChannels,
        tmdbEnrichedPreviewChannelIds,
        watchNextEnrichmentRepository,
    ) {
        visiblePreviewChannels = baseVisiblePreviewChannels
        if (!watchNextEnrichmentRepository.isTmdbConfigured) return@LaunchedEffect

        baseVisiblePreviewChannels.forEach { channel ->
            if (channel.id !in tmdbEnrichedPreviewChannelIds || channel.programs.isEmpty()) return@forEach
            val enrichedMedia = watchNextEnrichmentRepository.enrichMedia(channel.programs.map { it.media })
            val mediaBySourceId = enrichedMedia.associateBy { it.source.sourceId }
            val enrichedChannel = channel.copy(
                programs = channel.programs.map { program ->
                    program.copy(media = mediaBySourceId[program.media.source.sourceId] ?: program.media)
                },
            )
            visiblePreviewChannels = visiblePreviewChannels.map { current ->
                if (current.id == channel.id) enrichedChannel else current
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

    LaunchedEffect(section, searchRepository) {
        if (!searchRepository.isTmdbConfigured) return@LaunchedEffect
        when (section) {
            LauncherSection.Movies -> if (movieBrowseSections.isEmpty() && !isMovieBrowseLoading) {
                isMovieBrowseLoading = true
                movieBrowseSections = searchRepository.browseTmdb(MediaType.Movie)
                isMovieBrowseLoading = false
            }
            LauncherSection.Series -> if (seriesBrowseSections.isEmpty() && !isSeriesBrowseLoading) {
                isSeriesBrowseLoading = true
                seriesBrowseSections = searchRepository.browseTmdb(MediaType.Series)
                isSeriesBrowseLoading = false
            }
            else -> Unit
        }
    }

    val visibleMovieBrowseSections = remember(movieBrowseSections, movieDiscoveryRowKeys) {
        orderBrowseSections(movieBrowseSections, movieDiscoveryRowKeys)
    }
    val visibleSeriesBrowseSections = remember(seriesBrowseSections, seriesDiscoveryRowKeys) {
        orderBrowseSections(seriesBrowseSections, seriesDiscoveryRowKeys)
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

    val selectedDetailsItem = selectedDetailsSourceId?.let { selectedSourceId ->
        homeWatchNextItems.firstOrNull { it.media.source.sourceId == selectedSourceId }
    }
    val selectedDetailsMedia = selectedDetailsItem?.media ?: selectedSearchDetailsMedia ?: selectedHomeDetailsMedia

    LaunchedEffect(homeNavigationRequestGeneration) {
        if (homeNavigationRequestGeneration <= 0) return@LaunchedEffect

        if (section == LauncherSection.Home) {
            selectedDetailsSourceId?.let { sourceId ->
                watchNextFocusRestoreSourceId = sourceId
                watchNextFocusRestoreGeneration += 1
            }
            if (restoreHomeHeroOnDetailsClose && selectedHomeDetailsMedia != null) {
                homeHeroFocusRestoreGeneration += 1
            }
            selectedLiveTvServiceReference?.let { serviceReference ->
                liveTvFocusRestoreServiceReference = serviceReference
                liveTvFocusRestoreGeneration += 1
            }
        } else if (section == LauncherSection.Apps && homeAppsFocusRestoreKey != null) {
            homeAppsFocusRestoreGeneration += 1
        } else if (
            section == LauncherSection.LiveTv &&
            liveTvConfigurationReturnSection == LauncherSection.Home &&
            selectedLiveTvServiceReference == null
        ) {
            homeLiveTvConfigFocusRestoreGeneration += 1
        }

        selectedDetailsSourceId = null
        selectedSearchDetailsMedia = null
        selectedSearchDetailsResultId = null
        selectedHomeDetailsMedia = null
        selectedHomeDetailsSourceLabel = null
        restoreHomeHeroOnDetailsClose = false
        selectedLiveTvServiceReference = null
        liveTvPlayerSearchResultId = null
        openPlayerEpgInitially = false
        initialPlayerEpgProgramStartUtcMillis = null
        showHomeSettings = false
        discoverySettingsSection = null
        section = LauncherSection.Home
    }

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
                if (restoreHomeHeroOnDetailsClose && section == LauncherSection.Home) {
                    homeHeroFocusRestoreGeneration += 1
                }
                restoreHomeHeroOnDetailsClose = false
                selectedHomeDetailsMedia = null
                selectedHomeDetailsSourceLabel = null
            }
        }
    }
    val closeLiveTvPlayer: () -> Unit = {
        when {
            liveTvPlayerSearchResultId != null -> {
                searchFocusRestoreResultId = liveTvPlayerSearchResultId
                searchFocusRestoreGeneration += 1
            }
            section == LauncherSection.Home -> selectedLiveTvServiceReference?.let { serviceReference ->
                liveTvFocusRestoreServiceReference = serviceReference
                liveTvFocusRestoreGeneration += 1
            }
        }
        liveTvPlayerSearchResultId = null
        selectedLiveTvServiceReference = null
        openPlayerEpgInitially = false
        initialPlayerEpgProgramStartUtcMillis = null
    }

    BackHandler(enabled = selectedDetailsMedia != null, onBack = closeDetails)
    BackHandler(enabled = showHomeSettings && selectedDetailsMedia == null && selectedLiveTvServiceReference == null) {
        showHomeSettings = false
        section = LauncherSection.Home
    }
    BackHandler(
        enabled = discoverySettingsSection != null && selectedDetailsMedia == null && selectedLiveTvServiceReference == null,
    ) {
        discoverySettingsSection = null
    }
    BackHandler(
        enabled = !showHomeSettings && discoverySettingsSection == null && selectedDetailsMedia == null && selectedLiveTvServiceReference == null && section == LauncherSection.Apps,
    ) {
        if (homeAppsFocusRestoreKey != null) homeAppsFocusRestoreGeneration += 1
        section = LauncherSection.Home
    }
    BackHandler(
        enabled = !showHomeSettings && discoverySettingsSection == null && selectedDetailsMedia == null && selectedLiveTvServiceReference == null && section == LauncherSection.LiveTv,
    ) {
        if (liveTvConfigurationReturnSection == LauncherSection.Home) {
            homeLiveTvConfigFocusRestoreGeneration += 1
            section = LauncherSection.Home
        } else {
            settingsLiveTvActionFocusRestoreGeneration += 1
            section = LauncherSection.Settings
        }
    }

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
                    restoreHomeHeroOnDetailsClose = false
                    liveTvPlayerSearchResultId = result.id
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
                    restoreHomeHeroOnDetailsClose = false
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
                watchNextCardArtworkMode = watchNextCardArtworkMode,
                onSetWatchNextCardArtworkMode = homePreferences::setWatchNextCardArtworkMode,
                watchNextHeroArtworkMode = watchNextHeroArtworkMode,
                onSetWatchNextHeroArtworkMode = homePreferences::setWatchNextHeroArtworkMode,
                heroTextScrollSpeed = heroTextScrollSpeed,
                onSetHeroTextScrollSpeed = homePreferences::setHeroTextScrollSpeed,
                hiddenPreviewChannelIds = hiddenPreviewChannelIds,
                onSetPreviewChannelVisible = previewChannelPreferences::setVisible,
                onShowAllPreviewChannels = previewChannelPreferences::showAll,
                tmdbEnrichedPreviewChannelIds = tmdbEnrichedPreviewChannelIds,
                onSetPreviewChannelTmdbEnrichment = previewChannelPreferences::setTmdbEnrichmentEnabled,
                onBack = {
                    showHomeSettings = false
                    section = LauncherSection.Home
                },
                modifier = Modifier.padding(horizontal = 38.dp, vertical = 12.dp),
            )

            discoverySettingsSection != null -> {
                val settingsSection = requireNotNull(discoverySettingsSection)
                val mediaType = if (settingsSection == LauncherSection.Movies) MediaType.Movie else MediaType.Series
                val selectedRowKeys = if (mediaType == MediaType.Movie) movieDiscoveryRowKeys else seriesDiscoveryRowKeys
                ContentDiscoverySettingsScreen(
                    mediaType = mediaType,
                    selectedRowKeys = selectedRowKeys,
                    onSetVisible = { key, visible ->
                        tmdbDiscoveryPreferences.setVisible(mediaType, key, visible)
                    },
                    onMove = { key, delta -> tmdbDiscoveryPreferences.move(mediaType, key, delta) },
                    onReset = { tmdbDiscoveryPreferences.reset(mediaType) },
                    onBack = { discoverySettingsSection = null },
                    modifier = Modifier.padding(horizontal = 38.dp, vertical = 12.dp),
                )
            }

            else -> Box(modifier = Modifier.fillMaxSize()) {
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
                        onOpenAllApps = {
                            homeAppsFocusRestoreKey = "all-apps"
                            section = LauncherSection.Apps
                        },
                        onOpenWatchNext = openWatchNext,
                        onOpenWatchNextDetails = { item ->
                            restoreHomeHeroOnDetailsClose = false
                            liveTvFocusRestoreServiceReference = null
                            selectedSearchDetailsMedia = null
                            selectedSearchDetailsResultId = null
                            selectedHomeDetailsMedia = null
                            selectedDetailsSourceId = item.media.source.sourceId
                        },
                        onOpenMediaDetails = { media, label ->
                            restoreHomeHeroOnDetailsClose = true
                            selectedDetailsSourceId = null
                            selectedSearchDetailsMedia = null
                            selectedSearchDetailsResultId = null
                            selectedHomeDetailsMedia = media
                            selectedHomeDetailsSourceLabel = label
                        },
                        onOpenPreviewProgram = { _, program -> previewChannelsRepository.launch(program) },
                        onOpenLiveTv = {
                            liveTvConfigurationReturnSection = LauncherSection.Home
                            section = LauncherSection.LiveTv
                        },
                        onPlayLiveTvChannel = { channel ->
                            liveTvPlayerSearchResultId = null
                            watchNextFocusRestoreSourceId = null
                            openPlayerEpgInitially = false
                            initialPlayerEpgProgramStartUtcMillis = null
                            selectedLiveTvServiceReference = channel.serviceReference
                        },
                        onNavigationVisibilityChange = {},
                        watchNextCardArtworkMode = watchNextCardArtworkMode,
                        watchNextHeroArtworkMode = watchNextHeroArtworkMode,
                        heroTextScrollSpeed = heroTextScrollSpeed,
                        onLiveTvFocused = { channel ->
                            channel.now?.let { program ->
                                scope.launch {
                                    epgRepository.enrichProgram(channel.serviceReference, program.startUtcMillis)
                                }
                            }
                        },
                        watchNextListState = watchNextListState,
                        liveTvListState = liveTvListState,
                        appsListState = appsListState,
                        watchNextFocusRestoreSourceId = watchNextFocusRestoreSourceId,
                        watchNextFocusRestoreGeneration = watchNextFocusRestoreGeneration,
                        liveTvFocusRestoreServiceReference = liveTvFocusRestoreServiceReference,
                        liveTvFocusRestoreGeneration = liveTvFocusRestoreGeneration,
                        homeHeroFocusRestoreGeneration = homeHeroFocusRestoreGeneration,
                        appsFocusRestoreKey = homeAppsFocusRestoreKey,
                        appsFocusRestoreGeneration = homeAppsFocusRestoreGeneration,
                        liveTvConfigFocusRestoreGeneration = homeLiveTvConfigFocusRestoreGeneration,
                    )

                    LauncherSection.Movies -> ContentDiscoveryScreen(
                        title = "Filme entdecken",
                        subtitle = "Trends, beliebte Titel, Top-Bewertungen und Filmkategorien von TMDB.",
                        sections = visibleMovieBrowseSections,
                        isLoading = isMovieBrowseLoading,
                        tmdbConfigured = searchRepository.isTmdbConfigured,
                        onOpenResult = openSearchResult,
                        listState = movieBrowseListState,
                        focusRestoreResultId = searchFocusRestoreResultId,
                        focusRestoreGeneration = searchFocusRestoreGeneration,
                        heroTextScrollSpeed = heroTextScrollSpeed,
                    )

                    LauncherSection.Series -> ContentDiscoveryScreen(
                        title = "Serien entdecken",
                        subtitle = "Trends, beliebte Serien, Top-Bewertungen und Kategorien von TMDB.",
                        sections = visibleSeriesBrowseSections,
                        isLoading = isSeriesBrowseLoading,
                        tmdbConfigured = searchRepository.isTmdbConfigured,
                        onOpenResult = openSearchResult,
                        listState = seriesBrowseListState,
                        focusRestoreResultId = searchFocusRestoreResultId,
                        focusRestoreGeneration = searchFocusRestoreGeneration,
                        heroTextScrollSpeed = heroTextScrollSpeed,
                    )

                    LauncherSection.Search -> SearchScreen(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        localResults = mergedLocalSearchResults,
                        tmdbResults = pureTmdbSearchResults,
                        browseSections = emptyList(),
                        isTmdbLoading = isTmdbSearchLoading,
                        isBrowseLoading = false,
                        tmdbConfigured = searchRepository.isTmdbConfigured,
                        apps = apps,
                        onOpenResult = openSearchResult,
                        listState = searchListState,
                        focusRestoreResultId = searchFocusRestoreResultId,
                        focusRestoreGeneration = searchFocusRestoreGeneration,
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = GoogleTvTopNavigationHeight,
                        ),
                    )

                    LauncherSection.Apps -> AppsScreen(
                        apps = apps,
                        onOpenApp = openApp,
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = GoogleTvTopNavigationHeight,
                        ),
                    )

                    LauncherSection.Settings -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                top = GoogleTvTopNavigationHeight + 6.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
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
                            selectedCategory = settingsSelectedCategory,
                            onSelectCategory = { settingsSelectedCategory = it },
                            liveTvActionFocusRestoreGeneration = settingsLiveTvActionFocusRestoreGeneration,
                            onOpenLiveTv = {
                                liveTvConfigurationReturnSection = LauncherSection.Settings
                                section = LauncherSection.LiveTv
                            },
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
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = GoogleTvTopNavigationHeight,
                        ),
                    )
                }

                GoogleTvTopNavigation(
                    activeSection = section,
                    onSelect = { destination ->
                        showHomeSettings = false
                        discoverySettingsSection = null
                        section = destination
                    },
                    onOpenSectionSettings = { destination ->
                        when (destination) {
                            LauncherSection.Home -> {
                                discoverySettingsSection = null
                                section = LauncherSection.Home
                                showHomeSettings = true
                            }
                            LauncherSection.Movies,
                            LauncherSection.Series,
                            -> {
                                showHomeSettings = false
                                section = destination
                                discoverySettingsSection = destination
                            }
                            else -> Unit
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }
}

private fun orderBrowseSections(
    sections: List<SearchBrowseSection>,
    selectedKeys: List<String>,
): List<SearchBrowseSection> {
    val byKey = sections.associateBy(SearchBrowseSection::key)
    return selectedKeys.mapNotNull(byKey::get)
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
