package com.andreassamitsch.ilauncher.ui.preview

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.andreassamitsch.ilauncher.R
import com.andreassamitsch.ilauncher.data.home.HomePreferences
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifState
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind
import com.andreassamitsch.ilauncher.ui.GoogleTvTopNavigation
import com.andreassamitsch.ilauncher.ui.LauncherSection
import com.andreassamitsch.ilauncher.ui.home.HomeScreen
import com.andreassamitsch.ilauncher.ui.search.SearchScreen
import com.andreassamitsch.ilauncher.ui.theme.ILauncherTheme

/**
 * Debug-only deterministic 1080p fixture used by the GitHub TV visual-smoke job.
 * It deliberately avoids network/TvProvider/OpenWebif state so screenshots stay comparable.
 */
class UiPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val fixtureApps = fixtureApps()
        val screen = intent.getStringExtra(EXTRA_SCREEN) ?: SCREEN_HOME
        setContent {
            ILauncherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                ) {
                    when (screen) {
                        SCREEN_SEARCH_DISCOVER,
                        SCREEN_SEARCH_RESULTS,
                        -> {
                            var query by remember(screen) {
                                mutableStateOf(if (screen == SCREEN_SEARCH_RESULTS) "Batman" else "")
                            }
                            SearchScreen(
                                query = query,
                                onQueryChange = { query = it },
                                localResults = if (screen == SCREEN_SEARCH_RESULTS) fixtureSearchLocalResults() else emptyList(),
                                tmdbResults = if (screen == SCREEN_SEARCH_RESULTS) fixtureSearchTmdbResults() else emptyList(),
                                browseSections = if (screen == SCREEN_SEARCH_DISCOVER) fixtureSearchBrowseSections() else emptyList(),
                                isTmdbLoading = false,
                                isBrowseLoading = false,
                                tmdbConfigured = true,
                                apps = fixtureApps,
                                onOpenResult = {},
                                listState = rememberLazyListState(),
                                focusRestoreResultId = null,
                                focusRestoreGeneration = 0,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }

                        else -> {
                            val epgFallback = screen == SCREEN_HOME_EPG_FALLBACK
                            val channels = if (epgFallback) emptyList() else fixtureChannels()
                            val liveTvState = if (epgFallback) fixtureEpgFallbackState() else OpenWebifState()
                            val rowOrder = if (epgFallback) {
                                listOf(HomePreferences.ROW_LIVE_TV)
                            } else {
                                channels.map { HomePreferences.previewRowKey(it.id) } + HomePreferences.ROW_APPS
                            }
                            var activeSection by remember { mutableStateOf(LauncherSection.Home) }
                            Box(Modifier.fillMaxSize()) {
                                HomeScreen(
                                    apps = fixtureApps,
                                    watchNextItems = emptyList(),
                                    watchNextError = null,
                                    previewChannels = channels,
                                    previewChannelsError = null,
                                    hasTvListingsPermission = true,
                                    liveTvState = liveTvState,
                                    homeRowOrder = rowOrder,
                                    onMoveHomeApp = { _, _ -> },
                                    onRequestTvListingsPermission = {},
                                    onOpenApp = {},
                                    onOpenWatchNext = {},
                                    onOpenWatchNextDetails = {},
                                    onOpenMediaDetails = { _, _ -> },
                                    onOpenPreviewProgram = { _, _ -> },
                                    onOpenLiveTv = {},
                                    onPlayLiveTvChannel = {},
                                    onNavigationVisibilityChange = {},
                                )
                                GoogleTvTopNavigation(
                                    activeSection = activeSection,
                                    onSelect = { activeSection = it },
                                    onOpenHomeSettings = {},
                                    modifier = Modifier.align(Alignment.TopStart),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fixtureChannels(): List<AppContentChannel> = listOf(
        fixtureChannel("recommendations", "Empfehlungen", 0),
        fixtureChannel("continue", "Für dich", 1),
        fixtureChannel("top", "Top Filme", 2),
    )

    private fun fixtureChannel(id: String, title: String, shift: Int): AppContentChannel {
        val art = fixtureArtwork()
        val titles = fixtureTitles()
        val programs = titles.mapIndexed { index, _ ->
            val sourceIndex = (index + shift) % titles.size
            val uri = resourceUri(art[sourceIndex])
            AppContentProgram(
                sourceOrder = index,
                media = MediaItem(
                    id = "visual-$id-$index",
                    type = MediaType.Movie,
                    title = titles[sourceIndex],
                    overview = if (sourceIndex == 0) {
                        "Ein Astronaut erwacht allein auf einer Mission und muss herausfinden, warum er dort ist – und wie er die Erde retten kann."
                    } else {
                        "Eine kurze Beschreibung für den reproduzierbaren TV-Layout-Test."
                    },
                    releaseYear = 2026 - sourceIndex,
                    backdropUri = uri,
                    voteAverage = 7.8 - sourceIndex * 0.2,
                    source = MediaSource(
                        provider = "visual-fixture",
                        sourceId = "visual-$id-$index",
                        packageName = "fixture.video",
                    ),
                ),
            )
        }
        return AppContentChannel(
            id = id,
            sourceOrder = shift,
            packageName = "fixture.video",
            title = title,
            appLinkIntentUri = null,
            programs = programs,
        )
    }

    private fun fixtureEpgFallbackState(): OpenWebifState {
        val now = System.currentTimeMillis()
        return OpenWebifState(
            configured = true,
            receiverLabel = "TV",
            channels = listOf(
                LiveTvChannel(
                    serviceReference = "1:0:1:fixture",
                    name = "ATV HD",
                    now = LiveTvProgram(
                        eventId = 1L,
                        title = "The Rookie",
                        shortDescription = "EPG-Quellbild ohne TMDB-Verknüpfung",
                        startUtcMillis = now - 10 * 60_000L,
                        durationMillis = 55 * 60_000L,
                        imageUri = resourceUri(R.drawable.ui_fixture_epg_portrait),
                    ),
                ),
            ),
        )
    }

    private fun fixtureSearchBrowseSections(): List<SearchBrowseSection> {
        val titles = fixtureTitles()
        return listOf(
            SearchBrowseSection(
                key = "trending-tv",
                title = "Serien im Trend",
                items = titles.take(6).mapIndexed { index, title ->
                    searchItem("browse-tv-$index", title, index, subtitle = "Serie")
                },
            ),
            SearchBrowseSection(
                key = "trending-movies",
                title = "Filme im Trend",
                items = titles.drop(1).plus(titles.first()).take(6).mapIndexed { index, title ->
                    searchItem("browse-movie-$index", title, index + 1, subtitle = "Film")
                },
            ),
            SearchBrowseSection(
                key = "science-fiction",
                title = "Science-Fiction",
                items = titles.reversed().take(6).mapIndexed { index, title ->
                    searchItem("browse-scifi-$index", title, index + 2, subtitle = "Film")
                },
            ),
        )
    }

    private fun fixtureSearchLocalResults(): List<SearchItem> = listOf(
        searchItem(
            id = "search-watch-next",
            title = "The Batman",
            artIndex = 0,
            kind = SearchResultKind.WatchNext,
            subtitle = "Noch 1 Std. 12 Min.",
            sourceLabel = "Cloud",
        ),
        searchItem(
            id = "search-preview",
            title = "Batman Begins",
            artIndex = 1,
            kind = SearchResultKind.PreviewProgram,
            subtitle = "Film",
            sourceLabel = "Prime",
        ),
        searchItem(
            id = "search-epg",
            title = "Batman: The Animated Series",
            artIndex = 2,
            kind = SearchResultKind.EpgProgram,
            subtitle = "Heute, 20:15",
            sourceLabel = "TV",
        ),
        SearchItem(
            id = "search-app",
            kind = SearchResultKind.App,
            title = "Prime",
            subtitle = "App",
            packageName = "fixture.app.3",
        ),
    )

    private fun fixtureSearchTmdbResults(): List<SearchItem> = listOf(
        searchItem("search-tmdb-0", "Batman", 3, subtitle = "1989"),
        searchItem("search-tmdb-1", "The Dark Knight", 4, subtitle = "2008"),
        searchItem("search-tmdb-2", "The Batman", 5, subtitle = "2022"),
        searchItem("search-tmdb-3", "Batman Returns", 1, subtitle = "1992"),
        searchItem("search-tmdb-4", "Batman Forever", 2, subtitle = "1995"),
    )

    private fun searchItem(
        id: String,
        title: String,
        artIndex: Int,
        kind: SearchResultKind = SearchResultKind.Tmdb,
        subtitle: String? = null,
        sourceLabel: String? = null,
    ): SearchItem = SearchItem(
        id = id,
        kind = kind,
        title = title,
        subtitle = subtitle,
        artworkUri = resourceUri(fixtureArtwork()[artIndex % fixtureArtwork().size]),
        sourceLabel = sourceLabel,
    )

    private fun fixtureArtwork(): List<Int> = listOf(
        R.drawable.ui_fixture_hero,
        R.drawable.ui_fixture_card_blue,
        R.drawable.ui_fixture_card_gold,
        R.drawable.ui_fixture_card_purple,
        R.drawable.ui_fixture_card_blue,
        R.drawable.ui_fixture_card_gold,
    )

    private fun fixtureTitles(): List<String> = listOf(
        "Der Astronaut – Project Hail Mary",
        "Masters of the Universe",
        "Troja",
        "Braveheart",
        "Alternate Realities",
        "Der letzte Horizont",
    )

    private fun resourceUri(resId: Int): String = "android.resource://$packageName/$resId"

    private fun fixtureApps(): List<InstalledApp> {
        val names = listOf("TV", "Kodi", "Cloud", "Prime", "Serien", "ORF", "Media")
        val colors = listOf(
            Color.rgb(231, 67, 93),
            Color.rgb(63, 162, 210),
            Color.rgb(72, 191, 115),
            Color.rgb(56, 119, 198),
            Color.rgb(224, 77, 141),
            Color.rgb(103, 92, 190),
            Color.rgb(224, 150, 54),
        )
        return names.mapIndexed { index, label ->
            InstalledApp(
                packageName = if (index == 0) "fixture.video" else "fixture.app.$index",
                label = label,
                componentName = ComponentName(this, UiPreviewActivity::class.java),
                icon = fixtureIcon(colors[index], label.take(1)),
            )
        }
    }

    private fun fixtureIcon(color: Int, letter: String): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(48f, 48f, 46f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 42f
        paint.isFakeBoldText = true
        canvas.drawText(letter, 48f, 63f, paint)
        return bitmap
    }

    private companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_HOME = "home"
        const val SCREEN_HOME_EPG_FALLBACK = "home-epg-fallback"
        const val SCREEN_SEARCH_DISCOVER = "search-discover"
        const val SCREEN_SEARCH_RESULTS = "search-results"
    }
}
