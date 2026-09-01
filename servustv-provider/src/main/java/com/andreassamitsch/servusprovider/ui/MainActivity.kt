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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.andreassamitsch.servusprovider.BuildConfig
import com.andreassamitsch.servusprovider.data.ServusCategory
import com.andreassamitsch.servusprovider.data.ServusCurrentChannelSelectionStore
import com.andreassamitsch.servusprovider.data.ServusLiveChannel
import com.andreassamitsch.servusprovider.data.ServusNewsEpisode
import com.andreassamitsch.servusprovider.data.ServusNewsPolicy
import com.andreassamitsch.servusprovider.data.ServusNewsRepository
import com.andreassamitsch.servusprovider.data.ServusShow
import com.andreassamitsch.servusprovider.update.ServusInstallResult
import com.andreassamitsch.servusprovider.update.ServusUpdateManager
import com.andreassamitsch.servusprovider.update.ServusUpdateState
import com.andreassamitsch.servusprovider.work.ServusRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private lateinit var repository: ServusNewsRepository
    private lateinit var currentSelectionStore: ServusCurrentChannelSelectionStore
    private lateinit var updateManager: ServusUpdateManager
    private lateinit var statusText: TextView
    private lateinit var contentContainer: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var refreshButton: Button
    private lateinit var updateStatusText: TextView
    private lateinit var updateButton: Button
    private var updatePollingJob: Job? = null
    private var episodes: List<ServusNewsEpisode> = emptyList()
    private var categories: List<ServusCategory> = emptyList()
    private var liveChannels: List<ServusLiveChannel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ServusNewsRepository(applicationContext)
        currentSelectionStore = ServusCurrentChannelSelectionStore(applicationContext)
        updateManager = ServusUpdateManager(applicationContext)
        ServusRefreshWorker.schedule(applicationContext)
        setContentView(buildUi())
        renderCached()
        observeUpdates()
        scope.launch { updateManager.checkForUpdates() }
        refresh(forceCatalog = false)
    }

    override fun onResume() {
        super.onResume()
        if (::contentContainer.isInitialized) renderCached()
        if (::updateManager.isInitialized) updateManager.refreshDownloadState()
    }

    override fun onDestroy() {
        updatePollingJob?.cancel()
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
            text = "ServusTV"
            textSize = if (isTvDevice) 32f else 28f
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Aktuelles · Live TV · Sendungen · ${BuildConfig.VERSION_NAME}"
            textSize = if (isTvDevice) 18f else 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(14))
        })

        statusText = TextView(this).apply {
            textSize = if (isTvDevice) 16f else 14f
            setTextColor(Color.LTGRAY)
        }
        content.addView(statusText)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        content.addView(progress)

        refreshButton = Button(this).apply {
            text = "Jetzt aktualisieren"
            setOnClickListener { refresh(forceCatalog = true) }
        }
        content.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                if (isTvDevice) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )

        updateStatusText = TextView(this).apply {
            textSize = if (isTvDevice) 14f else 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(updateStatusText)

        updateButton = Button(this).apply {
            text = "Auf Updates prüfen"
            setOnClickListener { handleUpdateAction() }
        }
        content.addView(
            updateButton,
            LinearLayout.LayoutParams(
                if (isTvDevice) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(20)
            },
        )

        contentContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(
            contentContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (isTvDevice) refreshButton.requestFocus()
        return root
    }

    private fun renderCached() {
        categories = repository.cachedCategories()
        episodes = currentSelectionStore.effectiveEpisodes(
            categories = categories,
            legacyEpisodes = repository.cachedEpisodes(),
        )
        liveChannels = repository.cachedLiveChannels()
        val success = repository.lastSuccessMillis()
        val showCount = categories.flatMap { it.shows }.distinctBy { it.id }.size
        val selectedShowCount = currentSelectionStore.effectiveSelectedShowIds(categories).size
        statusText.text = buildString {
            if (success > 0L) {
                append("Letzte Aktualisierung: ")
                append(DateFormat.getDateTimeInstance().format(Date(success)))
            } else {
                append("Noch keine erfolgreiche Aktualisierung")
            }
            if (currentSelectionStore.isConfigured()) {
                append("\nAktuelles: $selectedShowCount Sendung")
                if (selectedShowCount != 1) append("en")
                append(" ausgewählt")
            }
            if (repository.tvChannelSupported()) {
                append("\nAndroid TV: Aktuelles + Live")
                if (showCount > 0) append(" + $showCount Sendungskanäle")
            } else {
                append("\nStandalone-Modus ohne Android-TV-Kanäle")
            }
            repository.lastError()?.takeIf { it.isNotBlank() }?.let { append("\nLetzter Datenfehler: $it") }
        }
        renderContent()
    }

    private fun renderContent() {
        contentContainer.removeAllViews()
        addSectionTitle("Aktuelles")
        if (episodes.isNotEmpty()) {
            addRail(episodes.take(MAX_CURRENT_UI_ITEMS).map(::buildEpisodeCard))
        } else {
            contentContainer.addView(TextView(this).apply {
                text = if (currentSelectionStore.isConfigured()) {
                    "Für Aktuelles ist derzeit keine Sendung ausgewählt oder verfügbar. Öffne eine Sendung und füge sie zu Aktuelles hinzu."
                } else {
                    "Noch keine aktuellen Sendungen verfügbar."
                }
                textSize = if (isTvDevice) 15f else 13f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, dp(10))
            })
        }
        if (liveChannels.isNotEmpty()) {
            addSectionTitle("Live TV")
            addRail(liveChannels.map(::buildLiveCard))
        }
        if (categories.isEmpty()) {
            addSectionTitle("Sendungen")
            val diagnostic = repository.catalogDiagnostic()
            contentContainer.addView(TextView(this).apply {
                text = if (diagnostic.isNullOrBlank()) {
                    "Sendungskatalog wurde noch nicht erfolgreich geladen. Bitte vollständigen Refresh starten."
                } else {
                    "Sendungskatalog nicht verfügbar.\n$diagnostic"
                }
                textSize = if (isTvDevice) 16f else 14f
                setTextColor(if (diagnostic?.startsWith("Katalogfehler") == true) Color.rgb(255, 190, 140) else Color.GRAY)
                setPadding(0, 0, 0, dp(16))
            })
        } else {
            categories.sortedBy { it.order }.forEach { category ->
                addSectionTitle(category.title)
                addRail(category.shows.map(::buildShowCard))
            }
        }
    }

    private fun addSectionTitle(title: String) {
        contentContainer.addView(TextView(this).apply {
            text = title
            textSize = if (isTvDevice) 24f else 20f
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(10))
        })
    }

    private fun addRail(cards: List<View>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        cards.forEach { card ->
            row.addView(
                card,
                LinearLayout.LayoutParams(
                    dp(if (isTvDevice) CARD_WIDTH_TV_DP else CARD_WIDTH_PHONE_DP),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(if (isTvDevice) 14 else 10) },
            )
        }
        contentContainer.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun buildEpisodeCard(episode: ServusNewsEpisode): View = buildMediaCard(
        artworkUri = episode.artworkUri,
        logoUri = episode.logoUri,
        eyebrow = ServusNewsPolicy.displayLabel(episode),
        title = episode.title,
        meta = buildEpisodeMeta(episode),
        onClick = { openPlayback(episode.id) },
    )

    private fun buildLiveCard(channel: ServusLiveChannel): View {
        val current = channel.currentProgram()
        val meta = current?.let { program -> "Jetzt: ${program.title}" } ?: channel.description.orEmpty()
        return buildMediaCard(
            artworkUri = channel.artworkUri ?: channel.squareArtworkUri,
            logoUri = channel.logoUri,
            eyebrow = "LIVE",
            title = channel.title,
            meta = meta,
            onClick = { openPlayback(channel.id) },
        )
    }

    private fun buildShowCard(show: ServusShow): View {
        val selected = currentSelectionStore.isSelected(show, categories)
        return buildMediaCard(
            artworkUri = show.artworkUri ?: show.squareArtworkUri,
            logoUri = show.logoUri,
            eyebrow = if (selected) "AKTUELLES" else null,
            title = show.title,
            meta = if (show.episodes.isEmpty()) "Mediathek" else "${show.episodes.size} aktuelle Videos",
            onClick = { openShow(show.id) },
        )
    }

    private fun buildMediaCard(
        artworkUri: String?,
        logoUri: String?,
        eyebrow: String?,
        title: String,
        meta: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val width = dp(if (isTvDevice) CARD_WIDTH_TV_DP else CARD_WIDTH_PHONE_DP)
        val imageHeight = width * 9 / 16
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(8), dp(8), dp(10))
            setCardBackground(this, false)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { view, focused -> setCardBackground(view, focused) }

            val image = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(30, 30, 30))
                isFocusable = false
                isClickable = false
            }
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imageHeight))
            ServusArtworkLoader.load(scope, image, artworkUri)

            if (!logoUri.isNullOrBlank()) {
                val logo = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_START
                    adjustViewBounds = true
                    setPadding(0, dp(8), 0, dp(2))
                    isFocusable = false
                }
                addView(logo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isTvDevice) 42 else 34)))
                ServusArtworkLoader.load(scope, logo, logoUri)
            }
            if (!eyebrow.isNullOrBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = eyebrow
                    textSize = if (isTvDevice) 13f else 11f
                    setTextColor(Color.LTGRAY)
                    maxLines = 1
                    setPadding(0, dp(7), 0, 0)
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = if (isTvDevice) 18f else 15f
                setTextColor(Color.WHITE)
                maxLines = 2
                setPadding(0, dp(4), 0, dp(3))
            })
            addView(TextView(this@MainActivity).apply {
                text = meta
                textSize = if (isTvDevice) 13f else 11f
                setTextColor(Color.GRAY)
                maxLines = 2
            })
        }
    }

    private fun refresh(forceCatalog: Boolean) {
        refreshButton.isEnabled = false
        progress.visibility = View.VISIBLE
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.refresh(forceCatalog = forceCatalog) }
            }
            progress.visibility = View.GONE
            refreshButton.isEnabled = true
            if (result.isSuccess) {
                renderCached()
            } else {
                renderCached()
                val message = result.exceptionOrNull()?.message ?: "Unbekannter Fehler"
                statusText.text = buildString {
                    append(statusText.text)
                    append("\n")
                    append(if (forceCatalog) "Voll-Refresh fehlgeschlagen: " else "Datenaktualisierung fehlgeschlagen: ")
                    append(message)
                }
            }
        }
    }

    private fun observeUpdates() {
        scope.launch {
            updateManager.state.collectLatest { state ->
                renderUpdateState(state)
                if (state is ServusUpdateState.Downloading) startUpdatePolling() else stopUpdatePolling()
            }
        }
    }

    private fun renderUpdateState(state: ServusUpdateState) {
        when (state) {
            ServusUpdateState.Idle -> {
                updateStatusText.text = "Version ${BuildConfig.VERSION_NAME}"
                updateButton.text = "Auf Updates prüfen"
                updateButton.isEnabled = true
            }
            ServusUpdateState.Checking -> {
                updateStatusText.text = "Suche nach neuer Version …"
                updateButton.text = "Prüfe …"
                updateButton.isEnabled = false
            }
            is ServusUpdateState.UpToDate -> {
                updateStatusText.text = "ServusTV ${state.versionName} ist aktuell."
                updateButton.text = "Auf Updates prüfen"
                updateButton.isEnabled = true
            }
            is ServusUpdateState.Available -> {
                updateStatusText.text = "Update ${state.info.versionName} verfügbar."
                updateButton.text = "Update herunterladen"
                updateButton.isEnabled = true
            }
            is ServusUpdateState.Downloading -> {
                updateStatusText.text = state.progressPercent?.let { "Update wird heruntergeladen: $it %" }
                    ?: "Update wird heruntergeladen …"
                updateButton.text = "Download läuft"
                updateButton.isEnabled = false
            }
            is ServusUpdateState.ReadyToInstall -> {
                updateStatusText.text = "Update ${state.info.versionName} ist bereit."
                updateButton.text = "Update installieren"
                updateButton.isEnabled = true
            }
            is ServusUpdateState.Error -> {
                updateStatusText.text = "Update: ${state.message}"
                updateButton.text = "Erneut prüfen"
                updateButton.isEnabled = true
            }
        }
    }

    private fun handleUpdateAction() {
        when (val state = updateManager.state.value) {
            ServusUpdateState.Idle,
            is ServusUpdateState.UpToDate,
            is ServusUpdateState.Error,
            -> scope.launch { updateManager.checkForUpdates() }

            ServusUpdateState.Checking -> Unit
            is ServusUpdateState.Available -> updateManager.startDownload(state.info)
            is ServusUpdateState.Downloading -> updateManager.refreshDownloadState()
            is ServusUpdateState.ReadyToInstall -> scope.launch {
                when (val result = updateManager.installDownloadedUpdate()) {
                    ServusInstallResult.Started -> updateStatusText.text = "Android-Systeminstaller geöffnet."
                    ServusInstallResult.PermissionRequired -> {
                        updateStatusText.text = "Bitte 'Installation aus dieser Quelle' für ServusTV erlauben und anschließend erneut installieren."
                    }
                    is ServusInstallResult.Error -> updateStatusText.text = "Update: ${result.message}"
                }
            }
        }
    }

    private fun startUpdatePolling() {
        if (updatePollingJob?.isActive == true) return
        updatePollingJob = scope.launch {
            while (updateManager.state.value is ServusUpdateState.Downloading) {
                delay(1_000L)
                updateManager.refreshDownloadState()
            }
        }
    }

    private fun stopUpdatePolling() {
        updatePollingJob?.cancel()
        updatePollingJob = null
    }

    private fun openPlayback(id: String) {
        startActivity(
            Intent(this, PlaybackActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://play/${Uri.encode(id)}")),
        )
    }

    private fun openShow(showId: String) {
        startActivity(
            Intent(this, ShowActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://show/${Uri.encode(showId)}")),
        )
    }

    private fun setCardBackground(view: View, focused: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (focused) Color.rgb(42, 42, 42) else Color.rgb(19, 19, 19))
            if (focused) setStroke(dp(2), Color.WHITE)
        }
        view.scaleX = if (focused && isTvDevice) 1.035f else 1f
        view.scaleY = if (focused && isTvDevice) 1.035f else 1f
    }

    private fun buildEpisodeMeta(episode: ServusNewsEpisode): String = buildList {
        episode.publishedAtMillis?.let { add(formatPublishedAt(it)) }
        add(formatDuration(episode.durationMillis))
    }.joinToString(" · ")

    private fun formatPublishedAt(millis: Long): String =
        SimpleDateFormat("dd.MM. · HH:mm", Locale.getDefault()).format(Date(millis))

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0) "$minutes:${seconds.toString().padStart(2, '0')} Min." else "$seconds Sek."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val CARD_WIDTH_TV_DP = 300
        const val CARD_WIDTH_PHONE_DP = 220
        const val MAX_CURRENT_UI_ITEMS = 20
    }
}
