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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.andreassamitsch.ilauncher.data.update.UpdateManager
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentChannelsLoadResult
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.WatchNextItem
import com.andreassamitsch.ilauncher.model.WatchNextLoadResult
import com.andreassamitsch.ilauncher.ui.settings.SettingsCategory
import com.andreassamitsch.ilauncher.ui.settings.SettingsScreen
import com.andreassamitsch.ilauncher.ui.theme.ILauncherTheme

/** Deterministic debug-only fixture for settings layout and action-affordance screenshots. */
class SettingsPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val apps = fixtureApps()
        val watchNext = fixtureWatchNext()
        val previewChannels = fixturePreviewChannels()
        val initialCategory = when (intent.getStringExtra("screen")) {
            "about" -> SettingsCategory.About
            "diagnostics" -> SettingsCategory.Diagnostics
            else -> SettingsCategory.Setup
        }

        setContent {
            ILauncherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                ) {
                    val updateManager = remember { UpdateManager(this@SettingsPreviewActivity) }
                    var selectedCategory by remember { mutableStateOf(initialCategory) }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 24.dp, end = 24.dp, top = 66.dp),
                    ) {
                        SettingsScreen(
                            updateManager = updateManager,
                            watchNextResult = watchNext,
                            previewChannelsResult = previewChannels,
                            installedApps = apps,
                            hiddenWatchNextPackages = emptySet(),
                            onSetWatchNextSourceVisible = { _, _ -> },
                            onShowAllWatchNextSources = {},
                            hiddenPreviewChannelIds = emptySet(),
                            onSetPreviewChannelVisible = { _, _ -> },
                            onShowAllPreviewChannels = {},
                            selectedCategory = selectedCategory,
                            onSelectCategory = { selectedCategory = it },
                            onOpenLiveTv = {},
                            hasTvListingsPermission = true,
                            onRequestTvListingsPermission = {},
                            tmdbConfigured = true,
                            enrichedWatchNextItems = emptyList(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    private fun fixtureWatchNext(): WatchNextLoadResult = WatchNextLoadResult(
        items = listOf(
            watchNext(1L, 0, "fixture.cloud", "The Last of Us"),
            watchNext(2L, 1, "fixture.prime", "Fallout"),
            watchNext(3L, 2, "fixture.kodi", "Andor"),
            watchNext(4L, 3, "fixture.cloud", "Severance"),
        ),
    )

    private fun watchNext(
        id: Long,
        order: Int,
        packageName: String,
        title: String,
    ): WatchNextItem = WatchNextItem(
        id = id,
        sourceOrder = order,
        packageName = packageName,
        programType = null,
        title = title,
        releaseDate = "2026-01-01",
        seasonDisplayNumber = "1",
        episodeDisplayNumber = "2",
        episodeTitle = "Testfolge",
        shortDescription = null,
        posterArtUri = null,
        thumbnailUri = null,
        logoUri = null,
        intentUri = null,
        durationMillis = 3_600_000L,
        playbackPositionMillis = 1_200_000L,
        watchNextType = 0,
        lastEngagementTimeUtcMillis = 1_000L + order,
    )

    private fun fixturePreviewChannels(): AppContentChannelsLoadResult = AppContentChannelsLoadResult(
        channels = listOf(
            AppContentChannel("prime-home", 0, "fixture.prime", "Prime Empfehlungen", null, emptyList()),
            AppContentChannel("cloud-new", 1, "fixture.cloud", "Neu bei Cloud", null, emptyList()),
            AppContentChannel("kodi-library", 2, "fixture.kodi", "Kodi Mediathek", null, emptyList()),
        ),
        queriedChannelCount = 3,
        systemBrowsableChannelCount = 3,
        queriedProgramCount = 18,
    )

    private fun fixtureApps(): List<InstalledApp> = listOf(
        fixtureApp("fixture.cloud", "CloudStream", Color.rgb(65, 145, 220)),
        fixtureApp("fixture.prime", "Prime Video", Color.rgb(55, 120, 200)),
        fixtureApp("fixture.kodi", "Kodi", Color.rgb(70, 165, 215)),
    )

    private fun fixtureApp(packageName: String, label: String, color: Int): InstalledApp = InstalledApp(
        packageName = packageName,
        label = label,
        componentName = ComponentName(this, SettingsPreviewActivity::class.java),
        icon = fixtureIcon(color, label.take(1)),
    )

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
}
