package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal fun watchNextRailKey(item: EnrichedWatchNextItem): String =
    if (item.sourceItem.id >= 0) "watch-next-${item.sourceItem.id}"
    else "watch-next-fallback-${item.media.source.sourceId}-${item.sourceItem.sourceOrder}"

/** A rail's last selected content survives disposal of both its card and its LazyRow. */
internal class HomeRailFocusMemory {
    private val selected = mutableMapOf<String, String>()
    private val attached = mutableStateMapOf<Pair<String, String>, FocusRequester>()
    private val previewStates = mutableMapOf<String, LazyListState>()

    fun selectedKey(row: String): String? = selected[row]
    fun previewState(row: String): LazyListState = previewStates.getOrPut(row) { LazyListState() }

    @Composable
    fun cardModifier(row: String, key: String): Modifier {
        val requester = remember(row, key) { FocusRequester() }
        val identity = row to key
        DisposableEffect(identity, requester) {
            attached[identity] = requester
            onDispose { if (attached[identity] === requester) attached.remove(identity) }
        }
        return Modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) selected[row] = key
        }
    }

    fun requestAttached(row: String, key: String): Boolean =
        attached[row to key]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false

    suspend fun awaitAttached(row: String, key: String): Boolean {
        val requester = withTimeoutOrNull(1000L) {
            snapshotFlow { attached[row to key] }.filterNotNull().first()
        } ?: return false
        return runCatching { requester.requestFocus() }.getOrDefault(false)
    }
}
