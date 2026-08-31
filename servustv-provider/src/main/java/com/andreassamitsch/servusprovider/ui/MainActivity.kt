package com.andreassamitsch.servusprovider.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.andreassamitsch.servusprovider.data.ServusNewsEpisode
import com.andreassamitsch.servusprovider.data.ServusNewsRepository
import com.andreassamitsch.servusprovider.work.ServusRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: ServusNewsRepository
    private lateinit var statusText: TextView
    private lateinit var episodesText: TextView
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
        val padding = dp(36)
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 9, 9))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.START
        }
        root.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        content.addView(TextView(this).apply {
            text = "Servus Nachrichten Provider"
            textSize = 30f
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Prototyp: ServusTV API → Android-TV-Kanal → Media3"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(22))
        })

        statusText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        content.addView(statusText)

        progress = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
        }
        content.addView(progress)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, dp(18))
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
        actions.addView(refreshButton)
        actions.addView(playButton)
        content.addView(actions)

        episodesText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.LTGRAY)
        }
        content.addView(episodesText)
        refreshButton.requestFocus()
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
            if (!lastError.isNullOrBlank()) append("\nLetzter Fehler: $lastError")
        }
        playButton.isEnabled = episodes.isNotEmpty()
        episodesText.text = if (episodes.isEmpty()) {
            "Noch keine Sendungen im lokalen Cache."
        } else {
            episodes.joinToString(separator = "\n\n") { episode ->
                "• ${episode.title}  (${episode.durationMillis / 60_000} Min.)"
            }
        }
    }

    private fun refresh() {
        refreshButton.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.refresh() }
            }
            progress.visibility = ProgressBar.GONE
            refreshButton.isEnabled = true
            if (result.isSuccess) {
                renderCached()
            } else {
                statusText.text = "Aktualisierung fehlgeschlagen: ${result.exceptionOrNull()?.message ?: "Unbekannter Fehler"}"
            }
        }
    }

    private fun openEpisode(episode: ServusNewsEpisode) {
        startActivity(
            Intent(this, PlaybackActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("iservus://play/${android.net.Uri.encode(episode.id)}")),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
