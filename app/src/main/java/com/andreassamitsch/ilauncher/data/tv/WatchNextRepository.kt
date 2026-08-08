package com.andreassamitsch.ilauncher.data.tv

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.util.Log
import com.andreassamitsch.ilauncher.model.WatchNextItem
import com.andreassamitsch.ilauncher.model.WatchNextLoadResult
import com.andreassamitsch.ilauncher.system.TvProviderPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private const val TAG = "TV_PROVIDER"

class WatchNextRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun observe(): Flow<WatchNextLoadResult> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }

        runCatching {
            resolver.registerContentObserver(
                TvContract.WatchNextPrograms.CONTENT_URI,
                true,
                observer,
            )
        }.onFailure { throwable ->
            Log.w(
                TAG,
                "Unable to register Watch Next observer (${throwable.javaClass.simpleName})",
            )
        }

        trySend(Unit)
        awaitClose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
        .conflate()
        .map { load() }
        .flowOn(Dispatchers.IO)

    fun load(): WatchNextLoadResult {
        if (!TvProviderPermissionManager.hasReadTvListings(appContext)) {
            Log.d(TAG, "Watch Next query skipped: READ_TV_LISTINGS not granted")
            return WatchNextLoadResult(
                items = emptyList(),
                errorMessage = "Android-Berechtigung für TV-Inhalte fehlt. Erlaube „TV-Programme/Kanäle lesen“, damit I Launcher Watch Next und App-Kanäle anderer Apps lesen kann.",
            )
        }

        return try {
            val rawRows = resolver.query(
                TvContract.WatchNextPrograms.CONTENT_URI,
                PROJECTION,
                null,
                null,
                SORT_ORDER,
            )?.use(::readCursor).orEmpty()
            val items = WatchNextMapper.map(rawRows)

            Log.d(TAG, "Watch Next query succeeded: ${items.size} rows, ordered by last engagement DESC")
            WatchNextLoadResult(items = items)
        } catch (securityException: SecurityException) {
            Log.w(
                TAG,
                "Watch Next query denied by TvProvider (${securityException.javaClass.simpleName})",
            )
            WatchNextLoadResult(
                items = emptyList(),
                errorMessage = "TvProvider-Zugriff verweigert (${securityException.javaClass.simpleName}).",
            )
        } catch (throwable: Throwable) {
            Log.e(
                TAG,
                "Watch Next query failed (${throwable.javaClass.simpleName})",
            )
            WatchNextLoadResult(
                items = emptyList(),
                errorMessage = "Watch Next konnte nicht gelesen werden (${throwable.javaClass.simpleName}).",
            )
        }
    }

    fun launch(item: WatchNextItem): Boolean {
        val rawIntent = item.intentUri?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            val intent = Intent.parseUri(rawIntent, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            Log.d(TAG, "Launched Watch Next row ${item.sourceOrder} from ${item.packageName ?: "unknown"}")
            true
        }.onFailure { throwable ->
            Log.w(
                TAG,
                "Unable to launch Watch Next row ${item.sourceOrder} from ${item.packageName ?: "unknown"} (${throwable.javaClass.simpleName})",
            )
        }.getOrDefault(false)
    }

    private fun readCursor(cursor: Cursor): List<WatchNextRawRow> {
        val rows = ArrayList<WatchNextRawRow>(cursor.count.coerceAtLeast(0))

        while (cursor.moveToNext()) {
            rows += WatchNextRawRow(
                id = cursor.long(BaseColumns._ID) ?: -1L,
                packageName = cursor.string(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME),
                title = cursor.string(TvContract.PreviewPrograms.COLUMN_TITLE),
                seasonDisplayNumber = cursor.string(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                episodeDisplayNumber = cursor.string(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                episodeTitle = cursor.string(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE),
                shortDescription = cursor.string(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION),
                posterArtUri = cursor.string(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI),
                thumbnailUri = cursor.string(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI),
                logoUri = cursor.string(TvContract.PreviewPrograms.COLUMN_LOGO_URI),
                intentUri = cursor.string(TvContract.PreviewPrograms.COLUMN_INTENT_URI),
                durationMillis = cursor.long(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS),
                playbackPositionMillis = cursor.long(
                    TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                ),
                watchNextType = cursor.int(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE),
                lastEngagementTimeUtcMillis = cursor.long(
                    TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                ),
            )
        }

        return rows
    }

    private fun Cursor.string(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.long(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun Cursor.int(columnName: String): Int? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    companion object {
        internal val SORT_ORDER: String =
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC"

        private val PROJECTION = arrayOf(
            BaseColumns._ID,
            TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_LOGO_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
    }
}
