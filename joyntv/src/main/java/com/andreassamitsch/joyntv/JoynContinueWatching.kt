package com.andreassamitsch.joyntv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

internal data class JoynContinueWatchingEntry(
    val assetId: String,
    val media: JoynMediaItem,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
}

internal object JoynContinueWatchingPolicy {
    private const val EPISODE_START_MS = 120_000L
    private const val MOVIE_START_MAX_MS = 120_000L
    private const val FINISHED_REMAINING_MS = 180_000L

    fun supports(media: JoynMediaItem): Boolean =
        media.type == JoynMediaType.MOVIE || media.type == JoynMediaType.EPISODE

    fun isFinished(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L || positionMs <= 0L) return false
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        return positionMs >= durationMs ||
            (durationMs >= 5 * 60_000L && remaining <= FINISHED_REMAINING_MS) ||
            positionMs.toDouble() / durationMs >= 0.97
    }

    fun shouldContinue(media: JoynMediaItem, positionMs: Long, durationMs: Long): Boolean {
        if (!supports(media) || durationMs <= 0L || isFinished(positionMs, durationMs)) return false
        val threshold = when (media.type) {
            JoynMediaType.MOVIE -> min(MOVIE_START_MAX_MS, (durationMs * 0.03).toLong().coerceAtLeast(30_000L))
            JoynMediaType.EPISODE -> EPISODE_START_MS
            else -> Long.MAX_VALUE
        }
        return positionMs >= threshold
    }
}

internal class JoynContinueWatchingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun items(): List<JoynContinueWatchingEntry> =
        decodeEntries(prefs.getString(KEY_ITEMS, null))
            .filterNot { it.assetId in pendingDeletes() }
            .sortedByDescending(JoynContinueWatchingEntry::updatedAt)

    @Synchronized
    fun find(assetId: String?): JoynContinueWatchingEntry? =
        assetId?.let { id -> items().firstOrNull { it.assetId == id } }

    @Synchronized
    fun upsert(
        assetId: String,
        media: JoynMediaItem,
        positionMs: Long,
        durationMs: Long,
        dirty: Boolean = true,
    ): JoynContinueWatchingEntry? {
        if (!JoynContinueWatchingPolicy.shouldContinue(media, positionMs, durationMs)) return null
        val current = decodeEntries(prefs.getString(KEY_ITEMS, null)).toMutableList()
        val previous = current.firstOrNull { it.assetId == assetId }
        current.removeAll { it.assetId == assetId }
        val entry = JoynContinueWatchingEntry(
            assetId = assetId,
            media = JoynResumeMediaMerger.merge(media, previous?.media),
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
            dirty = dirty || previous?.dirty == true,
        )
        current.add(entry)
        persist(current)
        return entry
    }

    @Synchronized
    fun replaceFromRemote(remote: List<JoynContinueWatchingEntry>): List<JoynContinueWatchingEntry> {
        val deleted = pendingDeletes()
        val local = decodeEntries(prefs.getString(KEY_ITEMS, null))
        val localById = local.associateBy { it.assetId }
        val dirtyById = local.filter { it.dirty }.associateBy { it.assetId }
        val merged = linkedMapOf<String, JoynContinueWatchingEntry>()
        remote.forEach { remoteEntry ->
            if (remoteEntry.assetId !in deleted) {
                val selected = dirtyById[remoteEntry.assetId] ?: remoteEntry.copy(dirty = false)
                merged[remoteEntry.assetId] = selected.copy(
                    media = JoynResumeMediaMerger.merge(selected.media, localById[remoteEntry.assetId]?.media),
                )
            }
        }
        local.filter { it.dirty && it.assetId !in deleted }.forEach { merged[it.assetId] = it }
        persist(merged.values.toList())
        return items()
    }

    @Synchronized
    fun remove(assetId: String, pendingRemoteDelete: Boolean): List<JoynContinueWatchingEntry> {
        val current = decodeEntries(prefs.getString(KEY_ITEMS, null)).filterNot { it.assetId == assetId }
        val edit = prefs.edit().putString(KEY_ITEMS, encodeEntries(current))
        if (pendingRemoteDelete) {
            val deleted = pendingDeletes().toMutableSet().apply { add(assetId) }
            edit.putString(KEY_PENDING_DELETES, JSONArray(deleted.toList()).toString())
        }
        edit.apply()
        return items()
    }

    @Synchronized
    fun dirtyItems(): List<JoynContinueWatchingEntry> = items().filter { it.dirty }

    @Synchronized
    fun markSynced(assetId: String) {
        val current = decodeEntries(prefs.getString(KEY_ITEMS, null))
        val updated = current.map { if (it.assetId == assetId) it.copy(dirty = false) else it }
        persist(updated)
    }

    @Synchronized
    fun pendingDeleteIds(): Set<String> = pendingDeletes()

    @Synchronized
    fun clearPendingDelete(assetId: String) {
        val deleted = pendingDeletes().toMutableSet().apply { remove(assetId) }
        prefs.edit().putString(KEY_PENDING_DELETES, JSONArray(deleted.toList()).toString()).apply()
    }

    private fun pendingDeletes(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_PENDING_DELETES, "[]"))
        buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

    private fun persist(entries: List<JoynContinueWatchingEntry>) {
        prefs.edit().putString(
            KEY_ITEMS,
            encodeEntries(entries.sortedByDescending(JoynContinueWatchingEntry::updatedAt).take(MAX_ITEMS)),
        ).apply()
    }

    companion object {
        private const val PREFS_NAME = "joyn_continue_watching_v1"
        private const val KEY_ITEMS = "items"
        private const val KEY_PENDING_DELETES = "pending_deletes"
        private const val MAX_ITEMS = 40

        private fun encodeEntries(entries: List<JoynContinueWatchingEntry>): String = JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("assetId", entry.assetId)
                        .put("positionMs", entry.positionMs)
                        .put("durationMs", entry.durationMs)
                        .put("updatedAt", entry.updatedAt)
                        .put("dirty", entry.dirty)
                        .put("media", encodeMedia(entry.media)),
                )
            }
        }.toString()

        private fun decodeEntries(raw: String?): List<JoynContinueWatchingEntry> = runCatching {
            if (raw.isNullOrBlank()) return@runCatching emptyList()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val assetId = json.optString("assetId").takeIf(String::isNotBlank) ?: continue
                    val media = json.optJSONObject("media")?.let(::decodeMedia) ?: continue
                    val positionMs = json.optLong("positionMs")
                    val durationMs = json.optLong("durationMs")
                    if (!JoynContinueWatchingPolicy.shouldContinue(media, positionMs, durationMs)) continue
                    add(
                        JoynContinueWatchingEntry(
                            assetId = assetId,
                            media = media,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            updatedAt = json.optLong("updatedAt").takeIf { it > 0L } ?: 0L,
                            dirty = json.optBoolean("dirty", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

        private fun encodeMedia(item: JoynMediaItem): JSONObject = JSONObject()
            .put("id", item.id)
            .put("title", item.title)
            .putNullable("description", item.description)
            .putNullable("path", item.path)
            .put("type", item.type.name)
            .putNullable("imageUrl", item.imageUrl)
            .putNullable("backdropUrl", item.backdropUrl)
            .putNullable("logoUrl", item.logoUrl)
            .putNullable("videoId", item.videoId)
            .putNullable("seasonId", item.seasonId)
            .putNullable("seriesTitle", item.seriesTitle)
            .putNullable("seasonNumber", item.seasonNumber)
            .putNullable("episodeNumber", item.episodeNumber)
            .putNullable("seriesId", item.seriesId)
            .putNullable("seriesPath", item.seriesPath)
            .put("licenseTypes", JSONArray(item.licenseTypes.toList()))
            .put("markings", JSONArray(item.markings.toList()))

        private fun decodeMedia(json: JSONObject): JoynMediaItem? {
            val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
            val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
            return JoynMediaItem(
                id = id,
                title = title,
                description = json.nullableString("description"),
                path = json.nullableString("path"),
                type = runCatching { JoynMediaType.valueOf(json.optString("type")) }
                    .getOrDefault(JoynMediaType.UNKNOWN),
                imageUrl = json.nullableString("imageUrl"),
                backdropUrl = json.nullableString("backdropUrl"),
                logoUrl = json.nullableString("logoUrl"),
                videoId = json.nullableString("videoId"),
                seasonId = json.nullableString("seasonId"),
                seriesTitle = json.nullableString("seriesTitle"),
                seasonNumber = json.nullableInt("seasonNumber"),
                episodeNumber = json.nullableInt("episodeNumber"),
                licenseTypes = json.optJSONArray("licenseTypes").toStringSet(),
                markings = json.optJSONArray("markings").toStringSet(),
                seriesId = json.nullableString("seriesId"),
                seriesPath = json.nullableString("seriesPath"),
            )
        }

        private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
            put(key, value ?: JSONObject.NULL)

        private fun JSONObject.nullableString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

        private fun JSONObject.nullableInt(key: String): Int? =
            if (isNull(key) || !has(key)) null else optInt(key)

        private fun JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            return buildSet {
                for (index in 0 until length()) {
                    optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
}

internal class JoynWatchNextPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun publish(entry: JoynContinueWatchingEntry) {
        if (!isSupportedDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!JoynContinueWatchingPolicy.shouldContinue(entry.media, entry.positionMs, entry.durationMs)) {
            remove(entry.assetId)
            return
        }
        if (isSuppressed(entry.assetId)) return

        val program = buildProgram(entry)
        val knownId = rowId(entry.assetId)
        if (knownId != null) {
            val updated = runCatching {
                appContext.contentResolver.update(
                    TvContractCompat.buildWatchNextProgramUri(knownId),
                    program.toContentValues(),
                    null,
                    null,
                )
            }.getOrDefault(0)
            if (updated > 0) return
            forgetRow(entry.assetId)
            suppress(entry.assetId)
            return
        }

        val uri = runCatching {
            appContext.contentResolver.insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                program.toContentValues(),
            )
        }.getOrNull() ?: return
        rememberRow(entry.assetId, ContentUris.parseId(uri))
    }

    fun sync(entries: List<JoynContinueWatchingEntry>) {
        if (!isSupportedDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val eligible = entries.filter {
            JoynContinueWatchingPolicy.shouldContinue(it.media, it.positionMs, it.durationMs)
        }
        val liveIds = eligible.mapTo(hashSetOf()) { it.assetId }
        knownRows().keys
            .filter { !it.startsWith(NEXT_KEY_PREFIX) && it !in liveIds }
            .forEach(::remove)
        eligible.forEach(::publish)
    }

    fun remove(assetId: String) {
        val id = rowId(assetId)
        if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                appContext.contentResolver.delete(
                    TvContractCompat.buildWatchNextProgramUri(id),
                    null,
                    null,
                )
            }
        }
        forgetRow(assetId)
    }


    fun publishNextEpisode(seriesKey: String, media: JoynMediaItem) {
        if (!isSupportedDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (media.type != JoynMediaType.EPISODE) return
        val storageKey = NEXT_KEY_PREFIX + seriesKey
        val title = media.seriesTitle?.takeIf(String::isNotBlank) ?: media.title
        val episodeText = buildList {
            add("Nächste Folge")
            media.seasonNumber?.let { add("S$it") }
            media.episodeNumber?.let { add("F$it") }
            media.title.takeIf { it != title }?.let(::add)
        }.joinToString(" · ")
        val launchUri = Uri.parse(
            PlayerActivity.vodIntent(appContext, media).toUri(Intent.URI_INTENT_SCHEME),
        )
        val program = WatchNextProgram.Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
            .setTitle(title)
            .setDescription(episodeText)
            .setIntentUri(launchUri)
            .setInternalProviderId("joyn-next:$seriesKey")
            .setContentId(media.videoId ?: media.id)
            .setLastPlaybackPositionMillis(0)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .apply {
                setEpisodeTitle(media.title)
                media.seasonNumber?.let(::setSeasonNumber)
                media.episodeNumber?.let(::setEpisodeNumber)
                val artwork = if (media.type == JoynMediaType.EPISODE) {
                    media.imageUrl ?: media.backdropUrl
                } else {
                    media.backdropUrl ?: media.imageUrl
                }
                artwork?.takeIf(String::isNotBlank)?.let { setPosterArtUri(Uri.parse(it)) }
            }
            .build()

        val knownId = rowId(storageKey)
        if (knownId != null) {
            val updated = runCatching {
                appContext.contentResolver.update(
                    TvContractCompat.buildWatchNextProgramUri(knownId),
                    program.toContentValues(),
                    null,
                    null,
                )
            }.getOrDefault(0)
            if (updated > 0) return
            forgetRow(storageKey)
        }

        val uri = runCatching {
            appContext.contentResolver.insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                program.toContentValues(),
            )
        }.getOrNull() ?: return
        rememberRow(storageKey, ContentUris.parseId(uri))
    }

    fun removeNextEpisode(seriesKey: String) {
        remove(NEXT_KEY_PREFIX + seriesKey)
    }

    private fun buildProgram(entry: JoynContinueWatchingEntry): WatchNextProgram {
        val media = entry.media
        val title = media.seriesTitle?.takeIf(String::isNotBlank) ?: media.title
        val episodeText = if (media.type == JoynMediaType.EPISODE) {
            listOfNotNull(
                media.seasonNumber?.let { "S$it" },
                media.episodeNumber?.let { "F$it" },
                media.title.takeIf { it != title },
            ).joinToString(" · ").takeIf(String::isNotBlank)
        } else {
            media.description
        }
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setType(
                if (media.type == JoynMediaType.EPISODE) {
                    TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                } else {
                    TvContractCompat.PreviewPrograms.TYPE_MOVIE
                },
            )
            .setTitle(title)
            .setDescription(episodeText ?: media.description)
            .setIntentUri(watchNextUri(entry.assetId))
            .setInternalProviderId("joyn:" + entry.assetId)
            .setContentId(entry.assetId)
            .setLastPlaybackPositionMillis(entry.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setDurationMillis(entry.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastEngagementTimeUtcMillis(entry.updatedAt)

        if (media.type == JoynMediaType.EPISODE) {
            builder.setEpisodeTitle(media.title)
            media.seasonNumber?.let(builder::setSeasonNumber)
            media.episodeNumber?.let(builder::setEpisodeNumber)
        }

        val artwork = if (media.type == JoynMediaType.EPISODE) {
            media.imageUrl ?: media.backdropUrl
        } else {
            media.backdropUrl ?: media.imageUrl
        }
        artwork?.takeIf(String::isNotBlank)?.let { builder.setPosterArtUri(Uri.parse(it)) }
        return builder.build()
    }

    private fun watchNextUri(assetId: String): Uri = Uri.Builder()
        .scheme("ilauncherjoyn")
        .authority("watchnext")
        .appendPath(assetId)
        .build()

    private fun isSupportedDevice(): Boolean {
        val pm = appContext.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }

    private fun rowId(assetId: String): Long? = knownRows()[assetId]

    private fun rememberRow(assetId: String, id: Long) {
        val rows = knownRows().toMutableMap().apply { put(assetId, id) }
        prefs.edit().putString(KEY_ROWS, JSONObject(rows).toString()).apply()
    }

    private fun forgetRow(assetId: String) {
        val rows = knownRows().toMutableMap().apply { remove(assetId) }
        prefs.edit().putString(KEY_ROWS, JSONObject(rows).toString()).apply()
    }

    private fun knownRows(): Map<String, Long> = runCatching {
        val json = JSONObject(prefs.getString(KEY_ROWS, "{}"))
        buildMap {
            json.keys().forEach { key ->
                json.optLong(key).takeIf { it > 0L }?.let { put(key, it) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun isSuppressed(assetId: String): Boolean = suppressed().contains(assetId)

    private fun suppress(assetId: String) {
        val values = suppressed().toMutableSet().apply { add(assetId) }
        prefs.edit().putString(KEY_SUPPRESSED, JSONArray(values.toList()).toString()).apply()
    }

    private fun suppressed(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_SUPPRESSED, "[]"))
        buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

    companion object {
        private const val PREFS_NAME = "joyn_watch_next_v1"
        private const val NEXT_KEY_PREFIX = "next:"
        private const val KEY_ROWS = "rows"
        private const val KEY_SUPPRESSED = "suppressed"
    }
}
