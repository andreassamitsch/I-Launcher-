package com.andreassamitsch.servusprovider.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andreassamitsch.servusprovider.data.ServusCurrentChannelSelectionStore
import com.andreassamitsch.servusprovider.data.ServusNewsEpisode
import com.andreassamitsch.servusprovider.data.ServusNewsRepository
import com.andreassamitsch.servusprovider.data.ServusShow
import com.andreassamitsch.servusprovider.tv.ServusChannelPublisher
import com.andreassamitsch.servusprovider.work.ServusRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShowActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    private lateinit var repository: ServusNewsRepository
    private lateinit var currentSelectionStore: ServusCurrentChannelSelectionStore
    private lateinit var channelPublisher: ServusChannelPublisher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ServusNewsRepository(applicationContext)
        currentSelectionStore = ServusCurrentChannelSelectionStore(applicationContext)
        channelPublisher = ServusChannelPublisher(applicationContext)
        val showId = intent?.data?.lastPathSegment?.takeIf { it.isNotBlank() }
        val show = showId?.let(repository::cachedShow)
        if (show == null) {
            finish()
            return
        }
        setContentView(buildUi(show))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(show: ServusShow): ScrollView {
        val padding = dp(if (isTvDevice) 36 else 18)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 9, 9))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val header = LinearLayout(this).apply {
            orientation = if (isTvDevice) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val heroWidth = if (isTvDevice) dp(440) else ViewGroup.LayoutParams.MATCH_PARENT
        val heroHeight = if (isTvDevice) dp(248) else dp(190)
        val artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(28, 28, 28))
        }
        header.addView(artwork, LinearLayout.LayoutParams(heroWidth, heroHeight))
        ServusArtworkLoader.load(scope, artwork, show.artworkUri ?: show.squareArtworkUri)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (isTvDevice) dp(28) else 0, if (isTvDevice) 0 else dp(18), 0, 0)
        }
        if (!show.logoUri.isNullOrBlank()) {
            val logo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
            }
            info.addView(logo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isTvDevice) 82 else 64)))
            ServusArtworkLoader.load(scope, logo, show.logoUri)
        }
        info.addView(TextView(this).apply {
            text = show.title
            textSize = if (isTvDevice) 30f else 24f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(6))
        })
        info.addView(TextView(this).apply {
            text = show.categoryTitle
            textSize = if (isTvDevice) 15f else 13f
            setTextColor(Color.LTGRAY)
        })
        show.description?.takeIf { it.isNotBlank() }?.let { description ->
            info.addView(TextView(this).apply {
                text = description
                textSize = if (isTvDevice) 16f else 14f
                setTextColor(Color.LTGRAY)
                maxLines = if (isTvDevice) 5 else 7
                setPadding(0, dp(12), 0, 0)
            })
        }

        val selectionButton = Button(this).apply {
            isAllCaps = false
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        fun renderSelectionButton() {
            val selected = currentSelectionStore.isSelected(show, repository.cachedCategories())
            selectionButton.text = if (selected) {
                "Aus Aktuelles entfernen"
            } else {
                "Zu Aktuelles hinzufügen"
            }
        }
        renderSelectionButton()
        selectionButton.setOnClickListener {
            val categories = repository.cachedCategories()
            val selected = currentSelectionStore.isSelected(show, categories)
            currentSelectionStore.setSelected(
                showId = show.id,
                selected = !selected,
                categories = categories,
            )
            renderSelectionButton()
            // Apply the local choice immediately to Android TV. The normal refresh worker then
            // refreshes the fast feed without making the UI wait on the network.
            runCatching { channelPublisher.publish(repository.cachedEpisodes()) }
            ServusRefreshWorker.enqueueNow(applicationContext)
        }
        info.addView(
            selectionButton,
            LinearLayout.LayoutParams(
                if (isTvDevice) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )
        info.addView(TextView(this).apply {
            text = "Die Auswahl gilt lokal für den Kanal „ServusTV Aktuelles“ und kann jederzeit geändert werden."
            textSize = if (isTvDevice) 13f else 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, 0)
        })

        header.addView(
            info,
            LinearLayout.LayoutParams(
                if (isTvDevice) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (isTvDevice) 1f else 0f,
            ),
        )
        content.addView(header)

        content.addView(TextView(this).apply {
            text = "Folgen & Videos"
            textSize = if (isTvDevice) 24f else 20f
            setTextColor(Color.WHITE)
            setPadding(0, dp(28), 0, dp(12))
        })

        if (show.episodes.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "Für diese Sendung sind aktuell keine abspielbaren Videos im Katalog verfügbar."
                textSize = if (isTvDevice) 16f else 14f
                setTextColor(Color.GRAY)
            })
            if (isTvDevice) selectionButton.requestFocus()
        } else {
            var first: View? = null
            show.episodes.forEach { episode ->
                val card = buildEpisodeCard(episode)
                if (first == null) first = card
                content.addView(
                    card,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(12)
                    },
                )
            }
            if (isTvDevice) first?.requestFocus()
        }
        return scroll
    }

    private fun buildEpisodeCard(episode: ServusNewsEpisode): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundForFocus(this, false)
            setOnClickListener { openPlayback(episode.id) }
            setOnFocusChangeListener { view, focused -> setBackgroundForFocus(view, focused) }
        }
        val width = dp(if (isTvDevice) 260 else 132)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        row.addView(image, LinearLayout.LayoutParams(width, width * 9 / 16))
        ServusArtworkLoader.load(scope, image, episode.artworkUri)

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isTvDevice) 18 else 12), 0, 0, 0)
        }
        text.addView(TextView(this).apply {
            this.text = episode.title
            textSize = if (isTvDevice) 19f else 15f
            setTextColor(Color.WHITE)
            maxLines = 2
        })
        text.addView(TextView(this).apply {
            this.text = buildEpisodeMeta(episode)
            textSize = if (isTvDevice) 14f else 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(5), 0, 0)
        })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildEpisodeMeta(episode: ServusNewsEpisode): String = buildList {
        episode.publishedAtMillis?.let { add(formatDate(it)) }
        add(formatDuration(episode.durationMillis))
    }.joinToString(" · ")

    private fun openPlayback(id: String) {
        startActivity(
            Intent(this, PlaybackActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://play/${Uri.encode(id)}")),
        )
    }

    private fun setBackgroundForFocus(view: View, focused: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (focused) Color.rgb(42, 42, 42) else Color.rgb(19, 19, 19))
            if (focused) setStroke(dp(2), Color.WHITE)
        }
        view.scaleX = if (focused && isTvDevice) 1.02f else 1f
        view.scaleY = if (focused && isTvDevice) 1.02f else 1f
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.getDefault()).format(Date(millis))

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1_000L
        val minutes = seconds / 60L
        val rest = seconds % 60L
        return "$minutes:${rest.toString().padStart(2, '0')} Min."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
