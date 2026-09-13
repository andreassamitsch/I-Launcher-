package com.andreassamitsch.joyntv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SeriesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = JoynMediaItem(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            description = intent.getStringExtra(EXTRA_DESCRIPTION),
            path = intent.getStringExtra(EXTRA_PATH),
            type = JoynMediaType.SERIES,
            imageUrl = intent.getStringExtra(EXTRA_IMAGE),
            backdropUrl = intent.getStringExtra(EXTRA_BACKDROP),
            logoUrl = intent.getStringExtra(EXTRA_LOGO),
        )
        if (item.id.isBlank() || item.title.isBlank() || item.path.isNullOrBlank()) {
            finish()
            return
        }
        val repository = JoynRepository(applicationContext)
        setContent {
            JoynTvTheme {
                SeriesScreen(
                    repository = repository,
                    initialItem = item,
                    onPlay = { episode -> openJoynMedia(this, episode) },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "series_id"
        private const val EXTRA_TITLE = "series_title"
        private const val EXTRA_DESCRIPTION = "series_description"
        private const val EXTRA_PATH = "series_path"
        private const val EXTRA_IMAGE = "series_image"
        private const val EXTRA_BACKDROP = "series_backdrop"
        private const val EXTRA_LOGO = "series_logo"

        fun intent(context: Context, item: JoynMediaItem): Intent =
            Intent(context, SeriesActivity::class.java).apply {
                putExtra(EXTRA_ID, item.id)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_DESCRIPTION, item.description)
                putExtra(EXTRA_PATH, item.path)
                putExtra(EXTRA_IMAGE, item.imageUrl)
                putExtra(EXTRA_BACKDROP, item.backdropUrl)
                putExtra(EXTRA_LOGO, item.logoUrl)
            }
    }
}

@Composable
private fun SeriesScreen(
    repository: JoynRepository,
    initialItem: JoynMediaItem,
    onPlay: (JoynMediaItem) -> Unit,
) {
    var details by remember { mutableStateOf<JoynSeriesDetails?>(null) }
    var selectedSeason by remember { mutableStateOf<JoynSeason?>(null) }
    var episodes by remember { mutableStateOf<List<JoynMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialItem.id) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) { repository.loadSeriesDetails(initialItem) }
        }.onSuccess {
            details = it
            selectedSeason = it.seasons.firstOrNull()
        }.onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    LaunchedEffect(selectedSeason?.id) {
        val season = selectedSeason ?: return@LaunchedEffect
        episodes = emptyList()
        runCatching {
            withContext(Dispatchers.IO) { repository.loadSeasonEpisodes(season.id) }
        }.onSuccess { episodes = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    val series = details?.series ?: initialItem
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080A0E))) {
        val compact = maxHeight < 520.dp
        AsyncImage(
            model = series.backdropUrl ?: series.imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.34f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x66080A0E), Color(0xEB080A0E), Color(0xFF080A0E)),
                ),
            ),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = if (compact) 28.dp else 54.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth(if (compact) 0.88f else 0.62f)
                    .padding(horizontal = if (compact) 28.dp else 64.dp),
            ) {
                series.logoUrl?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier.width(if (compact) 150.dp else 210.dp).height(if (compact) 54.dp else 76.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = series.title,
                    color = Color.White,
                    fontSize = if (compact) 30.sp else 42.sp,
                    lineHeight = if (compact) 34.sp else 46.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                series.description?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        color = Color(0xFFD7DBE3),
                        fontSize = if (compact) 14.sp else 16.sp,
                        lineHeight = if (compact) 19.sp else 22.sp,
                        maxLines = if (compact) 3 else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(if (compact) 28.dp else 48.dp))
            val horizontalPadding = if (compact) 28.dp else 64.dp
            when {
                loading -> Text(
                    "Seriendaten werden geladen …",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                error != null && details == null -> Text(
                    error.orEmpty(),
                    color = Color(0xFFFFC5C5),
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                else -> {
                    val seasons = details?.seasons.orEmpty()
                    if (seasons.size > 1) {
                        Text(
                            "Staffeln",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = horizontalPadding),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(seasons, key = { it.id }) { season ->
                                SeasonChip(
                                    season = season,
                                    selected = selectedSeason?.id == season.id,
                                    onClick = { selectedSeason = season },
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }

                    Text(
                        selectedSeason?.let { "Staffel ${it.number}" } ?: "Folgen",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (episodes.isEmpty()) {
                        Text(
                            if (selectedSeason == null) "Keine verfügbaren Folgen." else "Folgen werden geladen …",
                            color = Color(0xFFD7DBE3),
                            modifier = Modifier.padding(horizontal = horizontalPadding),
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
                        ) {
                            items(episodes, key = { it.id }) { episode ->
                                EpisodeCard(episode, compact) { onPlay(episode) }
                            }
                        }
                    }
                    error?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            it,
                            color = Color(0xFFFFC5C5),
                            modifier = Modifier.padding(horizontal = horizontalPadding),
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(54.dp))
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(season: JoynSeason, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected || focused) Color.White else Color(0xDD1A1F27))
            .border(1.dp, if (focused) Color.White else Color(0xFF4D5663), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            "Staffel ${season.number}",
            color = if (selected || focused) Color(0xFF11151B) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EpisodeCard(item: JoynMediaItem, compact: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .width(if (compact) 230.dp else 300.dp)
            .height(if (compact) 130.dp else 168.dp)
            .clip(shape)
            .background(Color(0xFF161A21))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF4C5562), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable(),
    ) {
        AsyncImage(
            model = item.imageUrl ?: item.backdropUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.82f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE080A0E))),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                text = listOfNotNull(
                    item.episodeNumber?.let { "F$it" },
                    item.title,
                ).joinToString(" · "),
                color = Color.White,
                fontSize = if (compact) 14.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
