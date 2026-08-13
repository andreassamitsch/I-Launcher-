package com.andreassamitsch.ilauncher.data.tmdb

import android.content.Context
import com.andreassamitsch.ilauncher.model.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TmdbDiscoveryRowKind {
    Trending,
    Popular,
    TopRated,
    Genre,
}

data class TmdbDiscoveryRowDefinition(
    val key: String,
    val title: String,
    val kind: TmdbDiscoveryRowKind,
    val genreId: String? = null,
)

object TmdbDiscoveryCatalog {
    fun rows(type: MediaType): List<TmdbDiscoveryRowDefinition> = when (type) {
        MediaType.Movie -> listOf(
            TmdbDiscoveryRowDefinition("movie-trending", "Filme im Trend", TmdbDiscoveryRowKind.Trending),
            TmdbDiscoveryRowDefinition("movie-popular", "Beliebte Filme", TmdbDiscoveryRowKind.Popular),
            TmdbDiscoveryRowDefinition("movie-top-rated", "Top bewertete Filme", TmdbDiscoveryRowKind.TopRated),
            TmdbDiscoveryRowDefinition("movie-genre-28", "Action", TmdbDiscoveryRowKind.Genre, "28"),
            TmdbDiscoveryRowDefinition("movie-genre-35", "Komödien", TmdbDiscoveryRowKind.Genre, "35"),
            TmdbDiscoveryRowDefinition("movie-genre-878", "Science-Fiction", TmdbDiscoveryRowKind.Genre, "878"),
            TmdbDiscoveryRowDefinition("movie-genre-53", "Thriller", TmdbDiscoveryRowKind.Genre, "53"),
            TmdbDiscoveryRowDefinition("movie-genre-10751", "Familie", TmdbDiscoveryRowKind.Genre, "10751"),
        )
        MediaType.Series -> listOf(
            TmdbDiscoveryRowDefinition("series-trending", "Serien im Trend", TmdbDiscoveryRowKind.Trending),
            TmdbDiscoveryRowDefinition("series-popular", "Beliebte Serien", TmdbDiscoveryRowKind.Popular),
            TmdbDiscoveryRowDefinition("series-top-rated", "Top bewertete Serien", TmdbDiscoveryRowKind.TopRated),
            TmdbDiscoveryRowDefinition("series-genre-18", "Drama", TmdbDiscoveryRowKind.Genre, "18"),
            TmdbDiscoveryRowDefinition("series-genre-80", "Krimi", TmdbDiscoveryRowKind.Genre, "80"),
            TmdbDiscoveryRowDefinition("series-genre-35", "Komödien", TmdbDiscoveryRowKind.Genre, "35"),
            TmdbDiscoveryRowDefinition("series-genre-10765", "Sci-Fi & Fantasy", TmdbDiscoveryRowKind.Genre, "10765"),
            TmdbDiscoveryRowDefinition("series-genre-99", "Dokumentationen", TmdbDiscoveryRowKind.Genre, "99"),
        )
        else -> emptyList()
    }

    fun selectedRows(type: MediaType, selectedKeys: List<String>): List<TmdbDiscoveryRowDefinition> {
        val available = rows(type).associateBy(TmdbDiscoveryRowDefinition::key)
        return selectedKeys.mapNotNull(available::get).distinctBy(TmdbDiscoveryRowDefinition::key)
    }
}

class TmdbDiscoveryPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _movieRowKeys = MutableStateFlow(load(MediaType.Movie))
    val movieRowKeys: StateFlow<List<String>> = _movieRowKeys.asStateFlow()

    private val _seriesRowKeys = MutableStateFlow(load(MediaType.Series))
    val seriesRowKeys: StateFlow<List<String>> = _seriesRowKeys.asStateFlow()

    fun setVisible(type: MediaType, key: String, visible: Boolean) {
        val available = TmdbDiscoveryCatalog.rows(type).map(TmdbDiscoveryRowDefinition::key)
        if (key !in available) return
        val current = current(type).filter { it in available }.ifEmpty { available }
        val next = when {
            visible && key !in current -> current + key
            !visible && key in current && current.size > 1 -> current - key
            else -> current
        }
        save(type, next)
    }

    fun move(type: MediaType, key: String, delta: Int) {
        val current = current(type)
        val from = current.indexOf(key)
        if (from < 0 || delta == 0) return
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (from == to) return
        save(
            type,
            current.toMutableList().apply {
                val item = removeAt(from)
                add(to, item)
            },
        )
    }

    fun reset(type: MediaType) {
        preferences.edit().remove(storageKey(type)).apply()
        state(type).value = TmdbDiscoveryCatalog.rows(type).map(TmdbDiscoveryRowDefinition::key)
    }

    private fun load(type: MediaType): List<String> {
        val defaults = TmdbDiscoveryCatalog.rows(type).map(TmdbDiscoveryRowDefinition::key)
        if (!preferences.contains(storageKey(type))) return defaults
        val selected = decode(preferences.getString(storageKey(type), null))
            .filter { it in defaults }
            .distinct()
        return selected.ifEmpty { defaults }
    }

    private fun save(type: MediaType, keys: List<String>) {
        preferences.edit().putString(storageKey(type), encode(keys)).apply()
        state(type).value = keys
    }

    private fun current(type: MediaType): List<String> = state(type).value

    private fun state(type: MediaType): MutableStateFlow<List<String>> = when (type) {
        MediaType.Movie -> _movieRowKeys
        MediaType.Series -> _seriesRowKeys
        else -> error("Discovery rows only exist for movies and series")
    }

    private fun storageKey(type: MediaType): String = when (type) {
        MediaType.Movie -> KEY_MOVIE_ROWS
        MediaType.Series -> KEY_SERIES_ROWS
        else -> error("Discovery rows only exist for movies and series")
    }

    private companion object {
        const val PREFS_NAME = "tmdb_discovery_preferences"
        const val KEY_MOVIE_ROWS = "movie_rows"
        const val KEY_SERIES_ROWS = "series_rows"
        const val SEPARATOR = "\u001F"

        fun encode(items: List<String>): String = items.joinToString(SEPARATOR)
        fun decode(raw: String?): List<String> = raw
            ?.split(SEPARATOR)
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
    }
}
