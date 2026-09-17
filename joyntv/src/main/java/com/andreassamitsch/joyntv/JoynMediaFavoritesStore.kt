package com.andreassamitsch.joyntv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent favorites for catalogue content such as series, movies, compilations and episodes.
 *
 * We store a small snapshot instead of only an id so the Favorites screen can be painted immediately
 * after app start without first finding the item again in a remote catalogue. Opening the favorite
 * still follows the normal Joyn navigation/playback path, so network data remains authoritative.
 */
internal class JoynMediaFavoritesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun items(): List<JoynMediaItem> = decode(prefs.getString(KEY_ITEMS, null))

    fun contains(item: JoynMediaItem): Boolean =
        items().any { favoriteKey(it) == favoriteKey(item) }

    fun toggle(item: JoynMediaItem): List<JoynMediaItem> = synchronized(this) {
        val current = items().toMutableList()
        val key = favoriteKey(item)
        val existingIndex = current.indexOfFirst { favoriteKey(it) == key }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(item)
        }
        val snapshot = current.toList()
        prefs.edit().putString(KEY_ITEMS, encode(snapshot)).apply()
        snapshot
    }

    companion object {
        private const val PREFS_NAME = "joyn_media_favorites_v1"
        private const val KEY_ITEMS = "items"

        fun favoriteKey(item: JoynMediaItem): String = "${item.type.name}:${item.id}"

        private fun encode(items: List<JoynMediaItem>): String = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
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
                        .putNullable("seasonNumber", item.seasonNumber)
                        .putNullable("episodeNumber", item.episodeNumber)
                        .put("licenseTypes", JSONArray().apply { item.licenseTypes.forEach(::put) })
                        .put("markings", JSONArray().apply { item.markings.forEach(::put) }),
                )
            }
        }.toString()

        private fun decode(value: String?): List<JoynMediaItem> = runCatching {
            if (value.isNullOrBlank()) return@runCatching emptyList()
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
                    val title = json.optString("title").takeIf(String::isNotBlank) ?: continue
                    add(
                        JoynMediaItem(
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
                            seasonNumber = json.nullableInt("seasonNumber"),
                            episodeNumber = json.nullableInt("episodeNumber"),
                            licenseTypes = json.optJSONArray("licenseTypes").toStringSet(),
                            markings = json.optJSONArray("markings").toStringSet(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

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
