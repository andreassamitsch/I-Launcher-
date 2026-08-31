package com.andreassamitsch.servusprovider.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.andreassamitsch.servusprovider.data.ServusNewsEpisode
import com.andreassamitsch.servusprovider.data.ServusNewsPolicy
import com.andreassamitsch.servusprovider.data.ServusNewsRepository
import com.andreassamitsch.servusprovider.work.ServusRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val artworkCache = object : LruCache<String, Bitmap>(ARTWORK_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private lateinit var repository: ServusNewsRepository
    private lateinit var statusText: TextView
    private lateinit var episodesContainer: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var refreshButton: Button
    private lateinit var playButton: Button
    private var episodes: List<ServusNewsEpisode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ServusNewsRepository(applicationContext)
        ServusRefreshWorker.schedule(applicationContext)
        setContentView(buildUi())
        renderCached()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val outerPadding = dp(if (isTvDevice) 36 else 18)
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 9, 9))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            gravity = Gravity.START
        }
        root.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        content.addView(TextView(this).apply {
            text = "ServusTV Aktuelles"
            textSize = if (isTvDevice) 30f else 26f
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Servus Nachrichten · 90 Sekunden · Der Wegscheider"
            textSize = if (isTvDevice) 18f else 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(18))
        })

        statusText = TextView(this).apply {
            textSize = if (isTvDevice) 17f else 15f
            setTextColor(Color.WHITE)
        }
        content.addView(statusText)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }
        content.addView(progress)

        val actions = LinearLayout(this).apply {
            orientation = if (isTvDevice) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(20))
        }
        refreshButton = Button(this).apply {
            text = "Jetzt aktualisieren"
            setOnClickListener { refresh() }
        }
        playButton = Button(this).apply {
            text = "Neueste Sendung abspielen"
            isEnabled = false
            setOnClickListener { episodes.firstOrNull()?.let(::openEpisode) }
        }
        if (isTvDevice) {
            actions.addView(refreshButton)
            actions.addView(playButton)
        } else {
            actions.addView(
                refreshButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            actions.addView(
                playButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                },
            )
        }
        content.addView(actions)

        episodesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(
            episodesContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (isTvDevice) refreshButton.requestFocus()
        return root
    }

    private fun renderCached() {
        episodes = repository.cachedEpisodes()
        val success = repository.lastSuccessMillis()
        val lastError = repository.lastError()
        statusText.text = buildString {
            if (success > 0) {
                append("Letzte erfolgreiche Aktualisierung: ")
                append(DateFormat.getDateTimeInstance().format(Date(success)))
            } else {
                append("Noch keine erfolgreiche Aktualisierung")
            }
            if (repository.tvChannelSupported()) {
                append("\nAndroid-TV-Kanal: ServusTV Aktuelles")
            } else {
                append("\nStandalone-Modus: Android-TV-Kanal ist auf diesem Gerät nicht verfügbar.")
            }
            if (!lastError.isNullOrBlank()) append("\nLetzter Datenfehler: $lastError")
        }
        playButton.isEnabled = episodes.isNotEmpty()
        renderEpisodes()
    }

    private fun renderEpisodes() {
        episodesContainer.removeAllViews()
        if (episodes.isEmpty()) {
            episodesContainer.addView(TextView(this).apply {
                text = "Noch keine Sendungen im lokalen Cache."
                textSize = if (isTvDevice) 17f else 15f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(8), 0, dp(16))
            })
            return
        }

        episodes.forEach { episode ->
            episodesContainer.addView(
                buildEpisodeCard(episode),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                },
            )
        }
    }

    private fun buildEpisodeCard(episode: ServusNewsEpisode): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            setCardBackground(this, focused = false)
            setOnClickListener { openEpisode(episode) }
            setOnFocusChangeListener { view, focused -> setCardBackground(view, focused) }
        }

        val imageWidth = dp(if (isTvDevice) 260 else 132)
        val imageHeight = (imageWidth * 9) / 16
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(34, 34, 34))
            isFocusable = false
            isClickable = true
            contentDescription = "${episode.title} abspielen"
            setOnClickListener { openEpisode(episode) }
        }
        row.addView(image, LinearLayout.LayoutParams(imageWidth, imageHeight))

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isTvDevice) 18 else 12), 0, 0, 0)
        }
        val showName = TextView(this).apply {
            text = ServusNewsPolicy.displayLabel(episode)
            textSize = if (isTvDevice) 14f else 12f
            setTextColor(Color.LTGRAY)
            isFocusable = false
            isClickable = true
            setOnClickListener { openEpisode(episode) }
        }
        val title = TextView(this).apply {
            text = episode.title
            textSize = if (isTvDevice) 20f else 16f
            setTextColor(Color.WHITE)
            maxLines = 2
            isFocusable = false
            isClickable = true
            setPadding(0, dp(4), 0, dp(5))
            setOnClickListener { openEpisode(episode) }
        }
        val meta = TextView(this).apply {
            text = buildString {
                append(formatPublishedAt(episode.publishedAtMillis))
                append(" · ")
                append(formatDuration(episode.durationMillis))
            }
            textSize = if (isTvDevice) 14f else 12f
            setTextColor(Color.GRAY)
        }
        textColumn.addView(showName)
        textColumn.addView(title)
        textColumn.addView(meta)
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        episode.artworkUri?.let { loadArtwork(image, it) }
        return row
    }

    private fun refresh() {
        refreshButton.isEnabled = false
        progress.visibility = View.VISIBLE
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.refresh() }
            }
            progress.visibility = View.GONE
            refreshButton.isEnabled = true
            if (result.isSuccess) {
                renderCached()
            } else {
                val message = result.exceptionOrNull()?.message ?: "Unbekannter Fehler"
                statusText.text = "Datenaktualisierung fehlgeschlagen: $message"
            }
        }
    }

    private fun loadArtwork(imageView: ImageView, url: String) {
        imageView.tag = url
        artworkCache.get(url)?.let { cached ->
            imageView.setImageBitmap(cached)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { downloadArtwork(url) } ?: return@launch
            artworkCache.put(url, bitmap)
            if (imageView.tag == url) imageView.setImageBitmap(bitmap)
        }
    }

    private fun downloadArtwork(url: String): Bitmap? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ServusNetwork.WEB_USER_AGENT)
            .header("Referer", "https://www.servustv.com/")
            .build()
        ServusNetwork.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            BitmapFactory.decodeStream(response.body.byteStream())
        }
    }.getOrNull()

    private fun setCardBackground(view: View, focused: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (focused) Color.rgb(42, 42, 42) else Color.rgb(20, 20, 20))
            if (focused) setStroke(dp(2), Color.WHITE)
        }
    }

    private fun openEpisode(episode: ServusNewsEpisode) {
        startActivity(
            Intent(this, PlaybackActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("iservus://play/${android.net.Uri.encode(episode.id)}")),
        )
    }

    private fun formatPublishedAt(millis: Long): String =
        SimpleDateFormat("dd.MM. · HH:mm", Locale.getDefault()).format(Date(millis))

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1_000L).coerceAtLeast(0L)
        val minutes = seconds / 60L
        val remainingSeconds = seconds % 60L
        return if (minutes > 0 && remainingSeconds > 0) {
            "$minutes:${remainingSeconds.toString().padStart(2, '0')} Min."
        } else if (minutes > 0) {
            "$minutes Min."
        } else {
            "$remainingSeconds Sek."
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ARTWORK_CACHE_KIB = 12 * 1024
    }
}
