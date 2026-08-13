package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.andreassamitsch.ilauncher.data.home.WatchNextArtworkMode
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifState
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.MediaItem

/** Debug-only overload kept for the deterministic visual fixture. */
@Composable
fun HomeScreen(
    apps: List<InstalledApp>,
    watchNextItems: List<EnrichedWatchNextItem>,
    watchNextError: String?,
    previewChannels: List<AppContentChannel>,
    previewChannelsError: String?,
    hasTvListingsPermission: Boolean,
    liveTvState: OpenWebifState,
    homeRowOrder: List<String>,
    onMoveHomeApp: (String, Int) -> Unit,
    onRequestTvListingsPermission: () -> Unit,
    onOpenApp: (InstalledApp) -> Unit,
    onOpenWatchNext: (EnrichedWatchNextItem) -> Unit,
    onOpenWatchNextDetails: (EnrichedWatchNextItem) -> Unit,
    onOpenMediaDetails: (MediaItem, String?) -> Unit,
    onOpenPreviewProgram: (AppContentChannel, AppContentProgram) -> Unit,
    onOpenLiveTv: () -> Unit,
    onPlayLiveTvChannel: (LiveTvChannel) -> Unit,
    onNavigationVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    watchNextCardArtworkMode: WatchNextArtworkMode = WatchNextArtworkMode.Episode,
    watchNextHeroArtworkMode: WatchNextArtworkMode = WatchNextArtworkMode.Episode,
    onLiveTvFocused: (LiveTvChannel) -> Unit = {},
    watchNextListState: LazyListState = rememberLazyListState(),
    liveTvListState: LazyListState = rememberLazyListState(),
    appsListState: LazyListState = rememberLazyListState(),
    watchNextFocusRestoreSourceId: String? = null,
    watchNextFocusRestoreGeneration: Int = 0,
    liveTvFocusRestoreServiceReference: String? = null,
    liveTvFocusRestoreGeneration: Int = 0,
) = HomeScreen(
    apps = apps,
    watchNextItems = watchNextItems,
    watchNextError = watchNextError,
    previewChannels = previewChannels,
    previewChannelsError = previewChannelsError,
    hasTvListingsPermission = hasTvListingsPermission,
    liveTvState = liveTvState,
    homeRowOrder = homeRowOrder,
    onMoveHomeApp = onMoveHomeApp,
    onRequestTvListingsPermission = onRequestTvListingsPermission,
    onOpenApp = onOpenApp,
    onOpenAllApps = {},
    onOpenWatchNext = onOpenWatchNext,
    onOpenWatchNextDetails = onOpenWatchNextDetails,
    onOpenMediaDetails = onOpenMediaDetails,
    onOpenPreviewProgram = onOpenPreviewProgram,
    onOpenLiveTv = onOpenLiveTv,
    onPlayLiveTvChannel = onPlayLiveTvChannel,
    onNavigationVisibilityChange = onNavigationVisibilityChange,
    modifier = modifier,
    watchNextCardArtworkMode = watchNextCardArtworkMode,
    watchNextHeroArtworkMode = watchNextHeroArtworkMode,
    onLiveTvFocused = onLiveTvFocused,
    watchNextListState = watchNextListState,
    liveTvListState = liveTvListState,
    appsListState = appsListState,
    watchNextFocusRestoreSourceId = watchNextFocusRestoreSourceId,
    watchNextFocusRestoreGeneration = watchNextFocusRestoreGeneration,
    liveTvFocusRestoreServiceReference = liveTvFocusRestoreServiceReference,
    liveTvFocusRestoreGeneration = liveTvFocusRestoreGeneration,
)
