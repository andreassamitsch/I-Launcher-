package com.andreassamitsch.joyntv

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        setContent {
            JoynTvTheme {
                SearchScreen(
                    repository = repository,
                    onOpen = { item -> openJoynMedia(this, item) },
                )
            }
        }
    }
}

@Composable
private fun SearchScreen(repository: JoynRepository, onOpen: (JoynMediaItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JoynMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun performSearch() {
        if (query.isBlank() || loading) return
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) { repository.searchMedia(query) }
            }.onSuccess { results = it }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080A0E))) {
        val compact = maxHeight < 520.dp
        val horizontalPadding = if (compact) 28.dp else 64.dp
        Column(
            Modifier.fillMaxSize().padding(top = if (compact) 28.dp else 54.dp),
        ) {
            Text(
                "Suche",
                color = Color.White,
                fontSize = if (compact) 30.sp else 42.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 17.sp,
                    ),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .weight(1f)
                                .background(Color(0xFF171C24), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF4F5966), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            if (query.isBlank()) {
                                Text("Titel, Serie oder Film", color = Color(0xFF929AA6), fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = if (loading) "Suche …" else "Suchen",
                    enabled = query.isNotBlank() && !loading,
                    onClick = ::performSearch,
                )
            }

            Spacer(Modifier.height(if (compact) 28.dp else 40.dp))
            when {
                error != null -> Text(
                    error.orEmpty(),
                    color = Color(0xFFFFC5C5),
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                loading -> Text(
                    "Joyn wird durchsucht …",
                    color = Color(0xFFD7DBE3),
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                results.isEmpty() && query.isNotBlank() -> Text(
                    "Noch keine Treffer. Suche starten oder Suchbegriff ändern.",
                    color = Color(0xFFD7DBE3),
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                results.isNotEmpty() -> {
                    Text(
                        "Ergebnisse",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
                    ) {
                        items(results, key = { it.id }) { item ->
                            SearchCard(item, compact) { onOpen(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    !enabled -> Color(0xFF242932)
                    focused -> Color.White
                    else -> Color(0xFF1D232C)
                },
            )
            .border(1.dp, if (focused) Color.White else Color(0xFF535D6A), shape)
            .onFocusChanged { focused = it.isFocused }
            .then(if (enabled) Modifier.clickable(onClick = onClick).focusable() else Modifier)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = if (focused && enabled) Color(0xFF11151B) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SearchCard(item: JoynMediaItem, compact: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .width(if (compact) 210.dp else 250.dp)
            .height(if (compact) 130.dp else 150.dp)
            .clip(shape)
            .background(Color(0xFF161A21))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF4D5663), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable(),
    ) {
        AsyncImage(
            model = item.backdropUrl ?: item.imageUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.82f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0080A0E))),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                item.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when (item.type) {
                    JoynMediaType.SERIES -> "Serie"
                    JoynMediaType.MOVIE -> "Film"
                    JoynMediaType.EPISODE -> "Folge"
                    JoynMediaType.SPORT -> "Sport"
                    else -> "Joyn"
                },
                color = Color(0xFFCED3DC),
                fontSize = 11.sp,
            )
        }
    }
}
