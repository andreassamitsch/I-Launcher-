package com.andreassamitsch.ilauncher.ui.home

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
    private var focused: Pair<String, String>? = null
    private var pending: Pair<String, String>? = null

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
            if (it.isFocused) {
                focused = identity
                if (pending?.first != row || pending == identity) selected[row] = key
                Log.d("HOME_FOCUS", "focused row=$row key=$key pending=$pending")
            } else if (focused == identity) focused = null
        }
    }

    // Use one restoration authority. Native focusRestorer can redirect an explicit request
    // to its own saved (or default) child when a newly composed rail is entered.
    fun restoreOnEnter(row: String): Modifier = Modifier.focusProperties {
        onEnter = {
            val key = pending?.takeIf { it.first == row }?.second ?: selected[row]
            if (key != null) requestAttached(row, key)
        }
    }

    fun beginRestore(row: String, key: String) {
        pending = row to key
        Log.d("HOME_FOCUS", "restore row=$row key=$key")
    }

    fun endRestore(row: String, key: String) {
        if (pending == (row to key)) pending = null
    }

    fun requestAttached(row: String, key: String): Boolean {
        val identity = row to key
        if (focused == identity) return true
        attached[identity]?.let { runCatching { it.requestFocus() } }
        return focused == identity
    }

    suspend fun awaitAttached(row: String, key: String): Boolean {
        val identity = row to key
        return withTimeoutOrNull(1000L) {
            snapshotFlow { attached[identity] }.filterNotNull().first()
            // A composed requester is not necessarily placed yet. Confirm actual focus,
            // not merely the return value of a potentially redirected request.
            while (!requestAttached(row, key)) withFrameNanos { }
            true
        } ?: false
    }
}
