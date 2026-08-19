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
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentChannelsLoadResult
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.system.TvProviderPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private const val PREVIEW_TAG = "TV_PROVIDER_CHANNELS"

class PreviewChannelsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun observe(): Flow<AppContentChannelsLoadResult> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val unregisterResumeRefresh = registerTvProviderResumeRefresh(appContext) {
            trySend(Unit)
        }

        val observedUris = listOf(
            TvContract.Channels.CONTENT_URI,
            TvContract.PreviewPrograms.CONTENT_URI,
        )
        observedUris.forEach { uri ->
            runCatching {
                resolver.registerContentObserver(uri, true, observer)
            }.onFailure { throwable ->
                Log.w(
                    PREVIEW_TAG,
                    "Unable to register preview content observer (${throwable.javaClass.simpleName})",
                )
            }
        }

        trySend(Unit)
        awaitClose {
            unregisterResumeRefresh()
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
        .conflate()
        .map { load() }
        .flowOn(Dispatchers.IO)

    fun load(): AppContentChannelsLoadResult {
        if (!TvProviderPermissionManager.hasReadTvListings(appContext)) {
            Log.d(PREVIEW_TAG, "Preview channel query skipped: READ_TV_LISTINGS not granted")
            return AppContentChannelsLoadResult(
                channels = emptyList(),
                errorMessage = "Android-Berechtigung für TV-Inhalte fehlt. App-Kanäle können erst nach der Freigabe gelesen werden.",
            )
        }

        return try {
            // TvContract.Channels.CONTENT_URI explicitly does not support SQL selection.
            // Read the local provider in its returned order and filter TYPE_PREVIEW in the mapper.
            val rawChannels = resolver.query(
                TvContract.Channels.CONTENT_URI,
                CHANNEL_PROJECTION,
                null,
                null,
                null,
            )?.use(::readChannels).orEmpty()
            val mapped = PreviewChannelsMapper.map(rawChannels)
            val rawPreviewChannels = rawChannels.filter { it.type == TvContract.Channels.TYPE_PREVIEW }
            val rawProgramCount = rawPreviewChannels.sumOf { it.programs.size }
            val systemBrowsableChannelCount = rawPreviewChannels.count { it.browsable == 1 }
            val displayableProgramCount = mapped.sumOf { it.programs.size }
            val usableChannelCount = mapped.count { it.programs.isNotEmpty() }

            Log.d(
                PREVIEW_TAG,
                "Preview channel query succeeded: ${rawPreviewChannels.size} preview channels, " +
                    "$systemBrowsableChannelCount system-browsable, $rawProgramCount raw programs, " +
                    "$usableChannelCount usable channels, $displayableProgramCount displayable programs",
            )
            AppContentChannelsLoadResult(
                channels = mapped,
                queriedChannelCount = rawPreviewChannels.size,
                systemBrowsableChannelCount = systemBrowsableChannelCount,
                queriedProgramCount = rawProgramCount,
            )
        } catch (securityException: SecurityException) {
            Log.w(
                PREVIEW_TAG,
                "Preview channel query denied by TvProvider (${securityException.javaClass.simpleName})",
            )
            AppContentChannelsLoadResult(
                channels = emptyList(),
                errorMessage = "TvProvider-Zugriff auf App-Kanäle verweigert (${securityException.javaClass.simpleName}).",
            )
        } catch (throwable: Throwable) {
            Log.e(
                PREVIEW_TAG,
                "Preview channel query failed (${throwable.javaClass.simpleName})",
            )
            AppContentChannelsLoadResult(
                channels = emptyList(),
                errorMessage = "App-Kanäle konnten nicht gelesen werden (${throwable.javaClass.simpleName}).",
            )
        }
    }

    fun launch(program: AppContentProgram): Boolean = launchIntentUri(
        rawIntent = program.media.source.intentUri,
        sourceLabel = program.media.source.packageName ?: "unknown",
    )

    fun launchChannel(channel: AppContentChannel): Boolean = launchIntentUri(
        rawIntent = channel.appLinkIntentUri,
        sourceLabel = channel.packageName ?: "unknown",
    )

    private fun launchIntentUri(rawIntent: String?, sourceLabel: String): Boolean {
        val value = rawIntent?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            val intent = Intent.parseUri(value, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            Log.d(PREVIEW_TAG, "Launched preview content from $sourceLabel")
            true
        }.onFailure { throwable ->
            Log.w(
                PREVIEW_TAG,
                "Unable to launch preview content from $sourceLabel (${throwable.javaClass.simpleName})",
            )
        }.getOrDefault(false)
    }

    private fun readChannels(cursor: Cursor): List<PreviewChannelRawRow> {
        val rows = ArrayList<PreviewChannelRawRow>(cursor.count.coerceAtLeast(0))
        var sourceOrder = 0
        while (cursor.moveToNext()) {
            val channelId = cursor.long(BaseColumns._ID) ?: continue
            val type = cursor.string(TvContract.Channels.COLUMN_TYPE)
            val browsable = cursor.int(TvContract.Channels.COLUMN_BROWSABLE)
            rows += PreviewChannelRawRow(
                id = channelId,
                sourceOrder = sourceOrder++,
                packageName = cursor.string(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME),
                displayName = cursor.string(TvContract.Channels.COLUMN_DISPLAY_NAME),
                appLinkIntentUri = cursor.string(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI),
                browsable = browsable,
                type = type,
                programs = if (type == TvContract.Channels.TYPE_PREVIEW) {
                    runCatching { loadPrograms(channelId) }
                        .onFailure { throwable ->
                            Log.d(
                                PREVIEW_TAG,
                                "Preview programs unavailable for channel $channelId (${throwable.javaClass.simpleName})",
                            )
                        }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                },
            )
        }
        return rows
    }

    private fun loadPrograms(channelId: Long): List<PreviewProgramRawRow> =
        resolver.query(
            TvContract.buildPreviewProgramsUriForChannel(channelId),
            PROGRAM_PROJECTION,
            null,
            null,
            PROGRAM_SORT_ORDER,
        )?.use(::readPrograms).orEmpty()

    private fun readPrograms(cursor: Cursor): List<PreviewProgramRawRow> {
        val rows = ArrayList<PreviewProgramRawRow>(cursor.count.coerceAtLeast(0))
        var sourceOrder = 0
        while (cursor.moveToNext()) {
            rows += PreviewProgramRawRow(
                id = cursor.long(BaseColumns._ID) ?: -1L,
                sourceOrder = sourceOrder++,
                packageName = cursor.string(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME),
                programType = cursor.int(TvContract.PreviewPrograms.COLUMN_TYPE),
                title = cursor.string(TvContract.PreviewPrograms.COLUMN_TITLE),
                releaseDate = cursor.string(TvContract.PreviewPrograms.COLUMN_RELEASE_DATE),
                seasonDisplayNumber = cursor.string(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                episodeDisplayNumber = cursor.string(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                episodeTitle = cursor.string(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE),
                shortDescription = cursor.string(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION),
                posterArtUri = cursor.string(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI),
                thumbnailUri = cursor.string(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI),
                logoUri = cursor.string(TvContract.PreviewPrograms.COLUMN_LOGO_URI),
                intentUri = cursor.string(TvContract.PreviewPrograms.COLUMN_INTENT_URI),
                durationMillis = cursor.long(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS),
                weight = cursor.int(TvContract.PreviewPrograms.COLUMN_WEIGHT),
                browsable = cursor.int(TvContract.PreviewPrograms.COLUMN_BROWSABLE),
                searchable = cursor.int(TvContract.PreviewPrograms.COLUMN_SEARCHABLE),
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
        internal const val PROGRAM_SORT_ORDER = "weight DESC"

        private val CHANNEL_PROJECTION = arrayOf(
            BaseColumns._ID,
            TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
            TvContract.Channels.COLUMN_BROWSABLE,
            TvContract.Channels.COLUMN_TYPE,
        )

        private val PROGRAM_PROJECTION = arrayOf(
            BaseColumns._ID,
            TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TYPE,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_RELEASE_DATE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_LOGO_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE,
            TvContract.PreviewPrograms.COLUMN_SEARCHABLE,
        )
    }
}
