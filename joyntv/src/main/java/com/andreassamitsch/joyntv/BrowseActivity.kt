package com.andreassamitsch.joyntv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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

class BrowseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_COLLECTION
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Joyn" }
        val repository = JoynRepository(applicationContext)

        setContent {
            JoynTvTheme {
                JoynBrowseScreen(
                    repository = repository,
                    mode = mode,
                    target = target,
                    fallbackTitle = title,
                    onBack = { finish() },
                    onOpen = { item -> openJoynMedia(this, item) },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_MODE = "browse_mode"
        private const val EXTRA_TARGET = "browse_target"
        private const val EXTRA_TITLE = "browse_title"

        internal const val MODE_CATEGORY = "category"
        internal const val MODE_CHANNEL = "channel"
        internal const val MODE_COLLECTION = "collection"
        internal const val MODE_COMPILATION = "compilation"

        private fun intent(context: Context, mode: String, target: String, title: String) =
            Intent(context, BrowseActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_TITLE, title)

        fun categoryIntent(context: Context, blockId: String, title: String): Intent =
            intent(context, MODE_CATEGORY, blockId, title)

        fun channelIntent(context: Context, path: String, title: String): Intent =
            intent(context, MODE_CHANNEL, path, title)

        fun collectionIntent(context: Context, path: String, title: String): Intent =
            intent(context, MODE_COLLECTION, path, title)

        fun compilationIntent(context: Context, path: String, title: String): Intent =
            intent(context, MODE_COMPILATION, path, title)
    }
}

@Composable
private fun JoynBrowseScreen(
    repository: JoynRepository,
    mode: String,
    target: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onOpen: (JoynMediaItem) -> Unit,
) {
    var page by remember(mode, target) { mutableStateOf<JoynCataloguePage?>(null) }
    var selected by remember(mode, target) { mutableStateOf<JoynMediaItem?>(null) }
    var error by remember(mode, target) { mutableStateOf<String?>(null) }
    var loading by remember(mode, target) { mutableStateOf(true) }

    LaunchedEffect(mode, target) {
        loading = true
        error = null
        page = null
        selected = null
        runCatching {
            withContext(Dispatchers.IO) {
                when (mode) {
                    BrowseActivity.MODE_CATEGORY -> repository.loadCategory(target, fallbackTitle)
                    BrowseActivity.MODE_CHANNEL -> repository.loadChannel(target, fallbackTitle)
                    BrowseActivity.MODE_COMPILATION -> repository.loadCompilation(target, fallbackTitle)
                    else -> repository.loadCollection(target, fallbackTitle)
                }
            }
        }.onSuccess { loaded ->
            val visible = loaded.forJoynUi()
            page = visible
            selected = visible.lanes.firstOrNull()?.items?.firstOrNull()
        }.onFailure { throwable ->
            error = throwable.message ?: throwable.javaClass.simpleName
        }
        loading = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080A0E))) {
        val compact = maxWidth < 720.dp || maxHeight < 520.dp
        val heroImage = selected?.backdropUrl ?: selected?.imageUrl

        AsyncImage(
            model = heroImage,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.30f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x66080A0E),
                    0.40f to Color(0xD9080A0E),
                    1f to Color(0xFF080A0E),
                ),
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (compact) 40.dp else 64.dp),
        ) {
            item {
                BrowseHeader(
                    title = page?.title ?: fallbackTitle,
                    compact = compact,
                    onBack = onBack,
                )
            }
            item { BrowseHero(selected, compact) }

            when {
                loading -> item { BrowseStatus("Inhalte werden geladen …", compact, false) }
                error != null -> item { BrowseStatus(error.orEmpty(), compact, true) }
                page?.lanes.isNullOrEmpty() -> item {
                    BrowseStatus("Für diesen Bereich sind keine Inhalte verfügbar.", compact, false)
                }
                mode == BrowseActivity.MODE_CHANNEL && compact -> item(key = "compact-channel-wall") {
                    CompactChannelMediathek(
                        lanes = page?.lanes.orEmpty(),
                        onFocused = { selected = it },
                        onOpen = onOpen,
                    )
                }
                else -> page?.lanes.orEmpty().forEach { lane ->
                    item(key = lane.id) {
                        lane.title.joynDisplayTitleOrNull()?.let { BrowseSectionTitle(it, compact) }
                        BrowseMediaRow(
                            lane = lane,
                            compact = compact,
                            onFocused = { selected = it },
                            onOpen = onOpen,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseHeader(title: String, compact: Boolean, onBack: () -> Unit) {
    val horizontal = if (compact) 28.dp else 64.dp
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = horizontal,
            end = horizontal,
            top = if (compact) 18.dp else 28.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowseAction("‹  Zurück", onBack)
        Spacer(Modifier.width(18.dp))
        Text(
            title,
            color = Color(0xFFD7DBE3),
            fontSize = if (compact) 15.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BrowseAction(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) Color.White else Color(0xD8171C24))
            .border(1.dp, if (focused) Color.White else Color(0xFF454E5A), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF11151B) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BrowseHero(item: JoynMediaItem?, compact: Boolean) {
    val horizontal = if (compact) 28.dp else 64.dp
    Column(
        Modifier.fillMaxWidth(if (compact) 0.88f else 0.62f).padding(
            start = horizontal,
            end = horizontal,
            top = if (compact) 24.dp else 38.dp,
            bottom = if (compact) 24.dp else 44.dp,
        ),
    ) {
        if (item == null) {
            Spacer(Modifier.height(if (compact) 48.dp else 72.dp))
            return@Column
        }
        val logo = item.logoUrl?.takeIf(String::isNotBlank)
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = item.title,
                modifier = Modifier.width(if (compact) 140.dp else 210.dp)
                    .height(if (compact) 46.dp else 68.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                item.title,
                color = Color.White,
                fontSize = if (compact) 28.sp else 40.sp,
                lineHeight = if (compact) 32.sp else 44.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            browseTypeLabel(item.type),
            color = Color(0xFFD7DBE3),
            fontSize = if (compact) 13.sp else 16.sp,
        )
        item.description?.takeIf(String::isNotBlank)?.let { description ->
            Spacer(Modifier.height(8.dp))
            Text(
                description,
                color = Color(0xFFE2E5EA),
                fontSize = if (compact) 13.sp else 15.sp,
                lineHeight = if (compact) 18.sp else 21.sp,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun browseTypeLabel(type: JoynMediaType): String = when (type) {
    JoynMediaType.MOVIE -> "Film"
    JoynMediaType.SERIES -> "Serie"
    JoynMediaType.EPISODE -> "Folge"
    JoynMediaType.CHANNEL -> "Mediathek"
    JoynMediaType.CATEGORY -> "Kategorie"
    JoynMediaType.COLLECTION -> "Sammlung"
    JoynMediaType.COMPILATION -> "Sendung"
    JoynMediaType.SPORT -> "Sport"
    else -> "Joyn"
}

@Composable
private fun BrowseSectionTitle(title: String, compact: Boolean) {
    Text(
        title,
        color = Color.White,
        fontSize = if (compact) 18.sp else 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            start = if (compact) 28.dp else 64.dp,
            end = if (compact) 28.dp else 64.dp,
            top = 8.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun BrowseMediaRow(
    lane: JoynLane,
    compact: Boolean,
    onFocused: (JoynMediaItem) -> Unit,
    onOpen: (JoynMediaItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(
            horizontal = if (compact) 28.dp else 64.dp,
            vertical = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        items(lane.items, key = { "${lane.id}:${it.id}" }) { item ->
            BrowseMediaCard(item, compact, onFocused) { onOpen(item) }
        }
    }
    Spacer(Modifier.height(if (compact) 20.dp else 30.dp))
}

@Composable
private fun CompactChannelMediathek(
    lanes: List<JoynLane>,
    onFocused: (JoynMediaItem) -> Unit,
    onOpen: (JoynMediaItem) -> Unit,
) {
    val popular = lanes.firstOrNull { it.id.endsWith(":highlights") }
    val alphabetical = lanes
        .filter { it.id.contains(":az") }
        .flatMap { it.items }
        .distinctBy { it.id }

    popular?.takeIf { it.items.isNotEmpty() }?.let { lane ->
        BrowseSectionTitle("Beliebte Sendungen", compact = true)
        BrowseMediaRow(
            lane = lane,
            compact = true,
            onFocused = onFocused,
            onOpen = onOpen,
        )
    }

    val wallItems = alphabetical.ifEmpty {
        lanes.filterNot { it.id.endsWith(":highlights") }.flatMap { it.items }.distinctBy { it.id }
    }
    if (wallItems.isNotEmpty()) {
        BrowseSectionTitle("Alle Sendungen A–Z", compact = true)
        BrowseMediaWall(wallItems, onFocused, onOpen)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseMediaWall(
    items: List<JoynMediaItem>,
    onFocused: (JoynMediaItem) -> Unit,
    onOpen: (JoynMediaItem) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { item ->
            key(item.id) {
                JoynMediaTile(
                    item = item,
                    compact = true,
                    cardHeight = 190.dp,
                    fallbackWidth = 142.dp,
                    fixedWidth = 142.dp,
                    imageAlpha = 0.92f,
                    centeredLogo = item.type == JoynMediaType.CHANNEL,
                    onFocused = onFocused,
                    onClick = { onOpen(item) },
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun BrowseMediaCard(
    item: JoynMediaItem,
    compact: Boolean,
    onFocused: (JoynMediaItem) -> Unit,
    onClick: () -> Unit,
) {
    JoynMediaTile(
        item = item,
        compact = compact,
        cardHeight = if (compact) 124.dp else 152.dp,
        fallbackWidth = if (compact) 220.dp else 270.dp,
        imageAlpha = if (item.type == JoynMediaType.CHANNEL) 0.78f else 0.9f,
        centeredLogo = item.type == JoynMediaType.CHANNEL,
        onFocused = onFocused,
        onClick = onClick,
    )
}

@Composable
private fun BrowseStatus(message: String, compact: Boolean, isError: Boolean) {
    Text(
        message,
        color = if (isError) Color(0xFFFFB4AB) else Color(0xFFB7BEC8),
        fontSize = if (compact) 14.sp else 16.sp,
        modifier = Modifier.padding(
            horizontal = if (compact) 28.dp else 64.dp,
            vertical = 20.dp,
        ),
    )
}
