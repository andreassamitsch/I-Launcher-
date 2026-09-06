package com.andreassamitsch.servusprovider.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.andreassamitsch.servusprovider.R
import com.andreassamitsch.servusprovider.data.ServusCurrentChannelSelectionStore
import com.andreassamitsch.servusprovider.data.ServusNewsEpisode
import com.andreassamitsch.servusprovider.data.ServusNewsRepository
import com.andreassamitsch.servusprovider.data.ServusShow
import com.andreassamitsch.servusprovider.data.ServusShowPager
import com.andreassamitsch.servusprovider.data.ServusShowPagingPolicy
import com.andreassamitsch.servusprovider.tv.ServusChannelPublisher
import com.andreassamitsch.servusprovider.work.ServusRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShowActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    private lateinit var repository: ServusNewsRepository
    private lateinit var pager: ServusShowPager
    private lateinit var currentSelectionStore: ServusCurrentChannelSelectionStore
    private lateinit var channelPublisher: ServusChannelPublisher

    private lateinit var artworkView: ImageView
    private lateinit var logoView: ImageView
    private lateinit var titleView: TextView
    private lateinit var categoryView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var episodeCardsContainer: LinearLayout
    private lateinit var loadStateText: TextView

    private var currentShow: ServusShow? = null
    private var hasMoreEpisodes = false
    private var loadingMore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ServusNewsRepository(applicationContext)
        pager = ServusShowPager(applicationContext)
        currentSelectionStore = ServusCurrentChannelSelectionStore(applicationContext)
        channelPublisher = ServusChannelPublisher(applicationContext)
        val showId = intent?.data?.lastPathSegment?.takeIf { it.isNotBlank() }
        val show = showId?.let(repository::cachedShow)
        if (show == null) {
            finish()
            return
        }
        currentShow = show
        setContentView(buildUi(show, loadingEpisodes = true))
        refreshOpenedShow(show)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshOpenedShow(cachedShow: ServusShow) {
        loadingMore = true
        updateLoadState()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { pager.refresh(cachedShow) }
            }
            if (isFinishing || isDestroyed) return@launch
            loadingMore = false
            val page = result.getOrNull()
            if (page != null) {
                val focusId = currentFocus?.tag as? String
                currentShow = page.show
                hasMoreEpisodes = page.hasMore
                updateHeader(page.show)
                replaceEpisodeCards(page.show.episodes, focusId)
                updateLoadState()
            } else {
                hasMoreEpisodes = false
                updateLoadState()
                Toast.makeText(this@ShowActivity, "Sendung konnte nicht aktualisiert werden.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadMoreEpisodes() {
        val show = currentShow ?: return
        if (loadingMore || !hasMoreEpisodes) return
        loadingMore = true
        updateLoadState()
        val focusId = currentFocus?.tag as? String
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { pager.loadNext(show.id) }
            }
            if (isFinishing || isDestroyed) return@launch
            loadingMore = false
            val page = result.getOrNull()
            if (page != null) {
                currentShow = page.show
                hasMoreEpisodes = page.hasMore
                updateHeader(page.show)
                replaceEpisodeCards(page.show.episodes, focusId)
            } else if (result.isFailure) {
                Toast.makeText(this@ShowActivity, "Weitere Folgen konnten nicht geladen werden.", Toast.LENGTH_SHORT).show()
            }
            updateLoadState()
        }
    }

    private fun buildUi(show: ServusShow, loadingEpisodes: Boolean): ScrollView {
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
        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(28, 28, 28))
        }
        header.addView(artworkView, LinearLayout.LayoutParams(heroWidth, heroHeight))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (isTvDevice) dp(28) else 0, if (isTvDevice) 0 else dp(18), 0, 0)
        }
        logoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
        }
        info.addView(
            logoView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isTvDevice) 82 else 64)),
        )
        titleView = TextView(this).apply {
            textSize = if (isTvDevice) 30f else 24f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(6))
        }
        info.addView(titleView)
        categoryView = TextView(this).apply {
            textSize = if (isTvDevice) 15f else 13f
            setTextColor(Color.LTGRAY)
        }
        info.addView(categoryView)
        descriptionView = TextView(this).apply {
            textSize = if (isTvDevice) 16f else 14f
            setTextColor(Color.LTGRAY)
            maxLines = if (isTvDevice) 5 else 7
            setPadding(0, dp(12), 0, 0)
        }
        info.addView(descriptionView)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val currentButton = buildIconButton(R.drawable.ic_star, "Zu Aktuelles hinzufügen")
        fun activeShow(): ServusShow = currentShow ?: show
        fun renderCurrentButton() {
            val selected = currentSelectionStore.isSelected(activeShow(), repository.cachedCategories())
            currentButton.contentDescription = if (selected) "Aus Aktuelles entfernen" else "Zu Aktuelles hinzufügen"
            setIconSelected(currentButton, selected)
        }
        renderCurrentButton()
        currentButton.setOnClickListener {
            val selectedShow = activeShow()
            val categories = repository.cachedCategories()
            val selected = currentSelectionStore.isSelected(selectedShow, categories)
            repository.setCurrentShowSelected(selectedShow.id, !selected)
            renderCurrentButton()
            runCatching { channelPublisher.publish(repository.cachedEpisodes()) }
            ServusRefreshWorker.enqueueNow(applicationContext)
            Toast.makeText(
                this,
                if (selected) "Aus Aktuelles entfernt" else "Zu Aktuelles hinzugefügt",
                Toast.LENGTH_SHORT,
            ).show()
        }
        actions.addView(currentButton, iconLayoutParams())

        val tvChannelSupported = repository.tvChannelSupported()
        val tvButton = buildIconButton(R.drawable.ic_tv, "Als Android-TV-Kanal veröffentlichen")
        fun renderTvButton() {
            val selected = repository.isShowChannelSelected(activeShow().id)
            tvButton.contentDescription = when {
                !tvChannelSupported -> "Android-TV-Kanal – nur auf Android TV verfügbar"
                selected -> "Android-TV-Kanal entfernen"
                else -> "Als Android-TV-Kanal veröffentlichen"
            }
            setIconSelected(tvButton, selected)
            if (!tvChannelSupported) tvButton.alpha = 0.45f
        }
        renderTvButton()
        tvButton.setOnClickListener {
            if (!tvChannelSupported) {
                Toast.makeText(
                    this,
                    "Android-TV-Kanäle sind nur auf Android TV verfügbar.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            val selectedShow = activeShow()
            val selected = repository.isShowChannelSelected(selectedShow.id)
            repository.setShowChannelSelected(selectedShow.id, !selected)
            renderTvButton()
            ServusRefreshWorker.enqueueNow(applicationContext)
            Toast.makeText(
                this,
                if (selected) "Android-TV-Kanal entfernt" else "Android-TV-Kanal aktiviert",
                Toast.LENGTH_SHORT,
            ).show()
        }
        actions.addView(tvButton, iconLayoutParams().apply { marginStart = dp(8) })
        info.addView(actions)

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

        episodeCardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(
            episodeCardsContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        loadStateText = TextView(this).apply {
            textSize = if (isTvDevice) 15f else 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(14))
        }
        content.addView(loadStateText)

        updateHeader(show)
        replaceEpisodeCards(show.episodes, focusId = null)
        loadingMore = loadingEpisodes
        updateLoadState()
        if (isTvDevice) {
            episodeCardsContainer.getChildAt(0)?.requestFocus() ?: currentButton.requestFocus()
        }
        return scroll
    }

    private fun updateHeader(show: ServusShow) {
        titleView.text = show.title
        categoryView.text = show.categoryTitle
        val description = show.description?.takeIf { it.isNotBlank() }
        descriptionView.text = description.orEmpty()
        descriptionView.visibility = if (description == null) View.GONE else View.VISIBLE

        ServusArtworkLoader.load(scope, artworkView, show.artworkUri ?: show.squareArtworkUri)
        val logo = show.logoUri?.takeIf { it.isNotBlank() }
        logoView.visibility = if (logo == null) View.GONE else View.VISIBLE
        if (logo != null) ServusArtworkLoader.load(scope, logoView, logo)
    }

    private fun replaceEpisodeCards(episodes: List<ServusNewsEpisode>, focusId: String?) {
        episodeCardsContainer.removeAllViews()
        var focusTarget: View? = null
        episodes.forEachIndexed { index, episode ->
            val card = buildEpisodeCard(episode, index)
            if (episode.id == focusId) focusTarget = card
            episodeCardsContainer.addView(
                card,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                },
            )
        }
        focusTarget?.post { focusTarget?.requestFocus() }
    }

    private fun updateLoadState() {
        val show = currentShow
        when {
            loadingMore && show?.episodes.isNullOrEmpty() -> {
                loadStateText.text = "Folgen werden geladen …"
                loadStateText.visibility = View.VISIBLE
            }
            loadingMore -> {
                loadStateText.text = "Weitere Folgen werden geladen …"
                loadStateText.visibility = View.VISIBLE
            }
            show?.episodes.isNullOrEmpty() -> {
                loadStateText.text = "Für diese Sendung sind aktuell keine abspielbaren Videos verfügbar."
                loadStateText.visibility = View.VISIBLE
            }
            hasMoreEpisodes -> {
                loadStateText.text = "Weitere Folgen werden beim Weiterblättern automatisch geladen."
                loadStateText.visibility = View.VISIBLE
            }
            else -> loadStateText.visibility = View.GONE
        }
    }

    private fun buildIconButton(iconRes: Int, description: String): ImageButton = ImageButton(this).apply {
        setImageResource(iconRes)
        contentDescription = description
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        isFocusable = true
        isClickable = true
        setIconBackground(this, focused = false)
        setOnFocusChangeListener { view, focused -> setIconBackground(view as ImageButton, focused) }
    }

    private fun iconLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(if (isTvDevice) 48 else 44), dp(if (isTvDevice) 48 else 44))

    private fun setIconSelected(button: ImageButton, selected: Boolean) {
        button.isSelected = selected
        setIconBackground(button, button.hasFocus())
    }

    private fun setIconBackground(button: ImageButton, focused: Boolean) {
        val selected = button.isSelected
        button.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(
                when {
                    focused -> Color.rgb(62, 62, 62)
                    selected -> Color.rgb(42, 42, 42)
                    else -> Color.rgb(22, 22, 22)
                },
            )
            if (focused) setStroke(dp(2), Color.WHITE)
            else if (selected) setStroke(dp(1), Color.rgb(120, 120, 120))
        }
        button.alpha = if (selected || focused) 1f else 0.8f
        button.scaleX = if (focused && isTvDevice) 1.06f else 1f
        button.scaleY = if (focused && isTvDevice) 1.06f else 1f
    }

    private fun buildEpisodeCard(episode: ServusNewsEpisode, index: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            tag = episode.id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundForFocus(this, false)
            setOnClickListener { openPlayback(episode.id) }
            setOnFocusChangeListener { view, focused ->
                setBackgroundForFocus(view, focused)
                if (focused && ServusShowPagingPolicy.shouldPrefetch(
                        focusedIndex = index,
                        itemCount = currentShow?.episodes?.size ?: 0,
                        hasMore = hasMoreEpisodes,
                    )
                ) {
                    loadMoreEpisodes()
                }
            }
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
        episode.description?.takeIf { it.isNotBlank() }?.let { detail ->
            text.addView(TextView(this).apply {
                this.text = detail
                textSize = if (isTvDevice) 14f else 12f
                setTextColor(Color.LTGRAY)
                maxLines = if (isTvDevice) 3 else 4
                setPadding(0, dp(7), 0, 0)
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildEpisodeMeta(episode: ServusNewsEpisode): String = buildList {
        when {
            episode.seasonNumber != null && episode.episodeNumber != null ->
                add("S${episode.seasonNumber} E${episode.episodeNumber}")
            episode.seasonNumber != null -> add("Staffel ${episode.seasonNumber}")
            episode.episodeNumber != null -> add("Folge ${episode.episodeNumber}")
        }
        when {
            episode.observedAvailableAtMillis != null -> add("Online erkannt ${formatDate(episode.observedAvailableAtMillis)}")
            episode.publishedAtMillis != null -> add("Verfügbar ab ${formatDate(episode.publishedAtMillis)}")
        }
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
