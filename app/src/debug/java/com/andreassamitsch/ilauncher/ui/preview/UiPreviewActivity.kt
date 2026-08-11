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
import com.andreassamitsch.ilauncher.R
import com.andreassamitsch.ilauncher.data.home.HomePreferences
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifState
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.ui.home.HomeScreen
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
        val channel = fixtureChannel()
        setContent {
            ILauncherTheme {
                HomeScreen(
                    apps = fixtureApps,
                    watchNextItems = emptyList(),
                    watchNextError = null,
                    previewChannels = listOf(channel),
                    previewChannelsError = null,
                    hasTvListingsPermission = true,
                    liveTvState = OpenWebifState(),
                    homeRowOrder = listOf(
                        HomePreferences.previewRowKey(channel.id),
                        HomePreferences.ROW_APPS,
                    ),
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
            }
        }
    }

    private fun fixtureChannel(): AppContentChannel {
        val art = listOf(
            R.drawable.ui_fixture_hero,
            R.drawable.ui_fixture_card_blue,
            R.drawable.ui_fixture_card_gold,
            R.drawable.ui_fixture_card_purple,
            R.drawable.ui_fixture_card_blue,
            R.drawable.ui_fixture_card_gold,
        )
        val titles = listOf(
            "Der Astronaut – Project Hail Mary",
            "Masters of the Universe",
            "Troja",
            "Braveheart",
            "Alternate Realities",
            "Der letzte Horizont",
        )
        val programs = titles.mapIndexed { index, title ->
            val resId = art[index]
            val uri = "android.resource://$packageName/$resId"
            AppContentProgram(
                sourceOrder = index,
                media = MediaItem(
                    id = "visual-$index",
                    type = MediaType.Movie,
                    title = title,
                    overview = if (index == 0) {
                        "Ein Astronaut erwacht allein auf einer Mission und muss herausfinden, warum er dort ist – und wie er die Erde retten kann."
                    } else {
                        "Eine kurze Beschreibung für den reproduzierbaren TV-Layout-Test."
                    },
                    releaseYear = 2026 - index,
                    backdropUri = uri,
                    voteAverage = 7.8 - index * 0.2,
                    source = MediaSource(
                        provider = "visual-fixture",
                        sourceId = "visual-$index",
                        packageName = "fixture.video",
                    ),
                ),
            )
        }
        return AppContentChannel(
            id = "visual-fixture",
            sourceOrder = 0,
            packageName = "fixture.video",
            title = "Empfehlungen",
            appLinkIntentUri = null,
            programs = programs,
        )
    }

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
}
