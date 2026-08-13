package com.andreassamitsch.ilauncher.ui.preview

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.andreassamitsch.ilauncher.R
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.data.tmdb.TmdbDiscoveryCatalog
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind
import com.andreassamitsch.ilauncher.ui.GoogleTvTopNavigation
import com.andreassamitsch.ilauncher.ui.LauncherSection
import com.andreassamitsch.ilauncher.ui.discover.ContentDiscoveryScreen
import com.andreassamitsch.ilauncher.ui.discover.ContentDiscoverySettingsScreen
import com.andreassamitsch.ilauncher.ui.theme.ILauncherTheme

/** Deterministic, network-free visual fixture for Movies/Series discovery and hidden settings. */
class DiscoveryPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val requestedType = if (intent.getStringExtra(EXTRA_TYPE) == TYPE_SERIES) {
            MediaType.Series
        } else {
            MediaType.Movie
        }
        val requestedScreen = intent.getStringExtra(EXTRA_SCREEN)

        setContent {
            ILauncherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                ) {
                    if (requestedScreen == SCREEN_SETTINGS) {
                        val selectedKeys = remember(requestedType) {
                            TmdbDiscoveryCatalog.defaultRowKeys(requestedType).take(1)
                        }
                        ContentDiscoverySettingsScreen(
                            mediaType = requestedType,
                            selectedRowKeys = selectedKeys,
                            onSetVisible = { _, _ -> },
                            onMove = { _, _ -> },
                            onReset = {},
                            onBack = {},
                        )
                    } else {
                        var activeSection by remember(requestedType) {
                            mutableStateOf(
                                if (requestedType == MediaType.Series) LauncherSection.Series
                                else LauncherSection.Movies,
                            )
                        }
                        Box(Modifier.fillMaxSize()) {
                            ContentDiscoveryScreen(
                                title = if (requestedType == MediaType.Movie) "Filme entdecken" else "Serien entdecken",
                                subtitle = if (requestedType == MediaType.Movie) {
                                    "Trends, beliebte Titel, Top-Bewertungen und Filmkategorien von TMDB."
                                } else {
                                    "Trends, beliebte Serien, Top-Bewertungen und Kategorien von TMDB."
                                },
                                sections = fixtureSections(requestedType),
                                isLoading = false,
                                tmdbConfigured = true,
                                onOpenResult = {},
                                listState = rememberLazyListState(),
                                focusRestoreResultId = null,
                                focusRestoreGeneration = 0,
                            )
                            GoogleTvTopNavigation(
                                activeSection = activeSection,
                                onSelect = { activeSection = it },
                                onOpenSectionSettings = {},
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun fixtureSections(type: MediaType): List<SearchBrowseSection> {
        val titles = if (type == MediaType.Movie) {
            listOf("Dune: Part Two", "Oppenheimer", "Furiosa", "The Batman", "Arrival", "Blade Runner 2049")
        } else {
            listOf("The Last of Us", "Fallout", "Severance", "Andor", "Shōgun", "Dark")
        }
        val art = listOf(
            R.drawable.ui_fixture_card_blue,
            R.drawable.ui_fixture_card_gold,
            R.drawable.ui_fixture_card_purple,
            R.drawable.ui_fixture_hero,
        )
        val rowTitles = if (type == MediaType.Movie) {
            listOf("Filme im Trend", "Beliebte Filme", "Top bewertete Filme", "Action", "Science-Fiction")
        } else {
            listOf("Serien im Trend", "Beliebte Serien", "Top bewertete Serien", "Drama", "Sci-Fi & Fantasy")
        }
        return rowTitles.mapIndexed { rowIndex, rowTitle ->
            SearchBrowseSection(
                key = "fixture-${type.name.lowercase()}-$rowIndex",
                title = rowTitle,
                items = titles.mapIndexed { index, title ->
                    val media = MediaItem(
                        id = "fixture-${type.name.lowercase()}-$rowIndex-$index",
                        type = type,
                        title = title,
                        overview = "Eine längere Beispielbeschreibung für $title. Sie zeigt im visuellen Test, dass der neue Discovery-Hero denselben Textbereich und dieselbe Scrolllogik wie die Home-Seite verwendet.",
                        releaseYear = 2026 - index,
                        tmdbId = 10_000 + rowIndex * 100 + index,
                        backdropUri = resourceUri(art[(index + rowIndex) % art.size]),
                        source = MediaSource(
                            provider = "tmdb_fixture",
                            sourceId = "fixture-${type.name.lowercase()}-$rowIndex-$index",
                        ),
                    )
                    SearchItem(
                        id = "search:${media.id}",
                        kind = SearchResultKind.Tmdb,
                        title = title,
                        subtitle = media.releaseYear?.toString(),
                        artworkUri = media.backdropUri,
                        sourceLabel = "TMDB",
                        media = media,
                    )
                },
            )
        }
    }

    private fun resourceUri(resId: Int): String = "android.resource://$packageName/$resId"

    private companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_SERIES = "series"
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_SETTINGS = "settings"
    }
}
