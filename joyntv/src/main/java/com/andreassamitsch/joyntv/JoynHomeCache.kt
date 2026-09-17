package com.andreassamitsch.joyntv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small stale-while-revalidate cache for the Joyn landing page and Live-TV inventory.
 *
 * The network remains authoritative. Cached data is only used to paint the UI immediately while a
 * background refresh is running. This is deliberately independent from Coil's image cache: the
 * expensive part on cold start is routing + Joyn metadata discovery, not decoding the thumbnails.
 */
internal class JoynHomeCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readLiveRows(): JoynLiveTvRows? = prefs.getString(KEY_LIVE_ROWS, null)
        ?.let(JoynHomeCacheCodec::decodeLiveRows)

    fun writeLiveRows(rows: JoynLiveTvRows) {
        prefs.edit().putString(KEY_LIVE_ROWS, JoynHomeCacheCodec.encodeLiveRows(rows)).apply()
    }

    fun readCatalogue(path: String): JoynCataloguePage? = prefs.getString(catalogueKey(path), null)
        ?.let { JoynHomeCacheCodec.decodeCatalogue(it, expectedPath = path) }

    fun writeCatalogue(path: String, page: JoynCataloguePage) {
        prefs.edit().putString(catalogueKey(path), JoynHomeCacheCodec.encodeCatalogue(path, page)).apply()
    }

    private fun catalogueKey(path: String): String = "catalogue_v1_${path.hashCode()}"

    companion object {
        private const val PREFS_NAME = "joyn_home_cache_v1"
        private const val KEY_LIVE_ROWS = "live_rows"
    }
}

/** Persistent user-selected Live-TV favorites. IDs already contain the Joyn market (AT/DE/CH). */
internal class JoynLiveFavoritesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ids(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()

    fun toggle(channelId: String): Set<String> = synchronized(this) {
        val updated = ids().toMutableSet()
        if (!updated.add(channelId)) updated.remove(channelId)
        val snapshot = updated.toSet()
        prefs.edit().putStringSet(KEY_IDS, snapshot).apply()
        snapshot
    }

    companion object {
        private const val PREFS_NAME = "joyn_live_favorites_v1"
        private const val KEY_IDS = "channel_ids"
    }
}

internal object JoynHomeCacheCodec {
    private const val VERSION = 1

    fun encodeLiveRows(rows: JoynLiveTvRows): String = JSONObject()
        .put("version", VERSION)
        .put("savedAt", System.currentTimeMillis())
        .put("combined", rows.combined.toJsonArray { it.toJson() })
        .put(
            "countries",
            JSONObject().apply {
                rows.byCountry.forEach { (country, channels) ->
                    put(country.name, channels.toJsonArray { it.toJson() })
                }
            },
        )
        .toString()

    fun decodeLiveRows(value: String): JoynLiveTvRows? = runCatching {
        val root = JSONObject(value)
        if (root.optInt("version") != VERSION) return@runCatching null
        val combined = root.optJSONArray("combined").toChannelList()
        val countries = root.optJSONObject("countries")
        val byCountry = JoynCountry.entries.associateWith { country ->
            countries?.optJSONArray(country.name).toChannelList()
        }
        JoynLiveTvRows(combined = combined, byCountry = byCountry)
    }.getOrNull()

    fun encodeCatalogue(path: String, page: JoynCataloguePage): String = JSONObject()
        .put("version", VERSION)
        .put("savedAt", System.currentTimeMillis())
        .put("path", path)
        .put("title", page.title)
        .put(
            "lanes",
            page.lanes.toJsonArray { lane ->
                JSONObject()
                    .put("id", lane.id)
                    .put("title", lane.title)
                    .put("items", lane.items.toJsonArray { it.toJson() })
            },
        )
        .toString()

    fun decodeCatalogue(value: String, expectedPath: String): JoynCataloguePage? = runCatching {
        val root = JSONObject(value)
        if (root.optInt("version") != VERSION || root.optString("path") != expectedPath) {
            return@runCatching null
        }
        val lanes = root.optJSONArray("lanes").toObjectList { laneJson ->
            JoynLane(
                id = laneJson.optString("id"),
                title = laneJson.optString("title"),
                items = laneJson.optJSONArray("items").toObjectList { it.toMediaItem() },
            )
        }
        JoynCataloguePage(title = root.optString("title"), lanes = lanes)
    }.getOrNull()

    private fun JoynLiveChannel.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("type", type)
        .putNullable("quality", quality)
        .put("markings", markings.toJsonArray { it })
        .putNullable("logoUrl", logoUrl)
        .putNullable("currentProgram", currentProgram?.toJson())
        .putNullable("nextProgram", nextProgram?.toJson())

    private fun JSONObject.toLiveChannel(): JoynLiveChannel = JoynLiveChannel(
        id = optString("id"),
        title = optString("title"),
        type = optString("type"),
        quality = nullableString("quality"),
        markings = optJSONArray("markings").toStringSet(),
        logoUrl = nullableString("logoUrl"),
        currentProgram = optJSONObject("currentProgram")?.toProgram(),
        nextProgram = optJSONObject("nextProgram")?.toProgram(),
    )

    private fun JoynProgram.toJson(): JSONObject = JSONObject()
        .put("title", title)
        .putNullable("subtitle", subtitle)
        .putNullable("imageUrl", imageUrl)
        .putNullable("startEpochSeconds", startEpochSeconds)
        .putNullable("endEpochSeconds", endEpochSeconds)

    private fun JSONObject.toProgram(): JoynProgram = JoynProgram(
        title = optString("title"),
        subtitle = nullableString("subtitle"),
        imageUrl = nullableString("imageUrl"),
        startEpochSeconds = nullableLong("startEpochSeconds"),
        endEpochSeconds = nullableLong("endEpochSeconds"),
    )

    private fun JoynMediaItem.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .putNullable("description", description)
        .putNullable("path", path)
        .put("type", type.name)
        .putNullable("imageUrl", imageUrl)
        .putNullable("backdropUrl", backdropUrl)
        .putNullable("logoUrl", logoUrl)
        .putNullable("videoId", videoId)
        .putNullable("seasonId", seasonId)
        .putNullable("seasonNumber", seasonNumber)
        .putNullable("episodeNumber", episodeNumber)
        .put("licenseTypes", licenseTypes.toJsonArray { it })
        .put("markings", markings.toJsonArray { it })

    private fun JSONObject.toMediaItem(): JoynMediaItem = JoynMediaItem(
        id = optString("id"),
        title = optString("title"),
        description = nullableString("description"),
        path = nullableString("path"),
        type = runCatching { JoynMediaType.valueOf(optString("type")) }.getOrDefault(JoynMediaType.UNKNOWN),
        imageUrl = nullableString("imageUrl"),
        backdropUrl = nullableString("backdropUrl"),
        logoUrl = nullableString("logoUrl"),
        videoId = nullableString("videoId"),
        seasonId = nullableString("seasonId"),
        seasonNumber = nullableInt("seasonNumber"),
        episodeNumber = nullableInt("episodeNumber"),
        licenseTypes = optJSONArray("licenseTypes").toStringSet(),
        markings = optJSONArray("markings").toStringSet(),
    )

    private fun JSONArray?.toChannelList(): List<JoynLiveChannel> =
        toObjectList { it.toLiveChannel() }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun <T> JSONArray?.toObjectList(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { add(mapper(it)) }
            }
        }
    }

    private fun <T> Iterable<T>.toJsonArray(mapper: (T) -> Any?): JSONArray {
        val array = JSONArray()
        this.forEach { item -> array.put(mapper(item)) }
        return array
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)
}
