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
    NowPlaying,
    Upcoming,
    AiringToday,
    OnTheAir,
    Genre,
}

enum class TmdbTrendWindow(val apiValue: String) {
    Day("day"),
    Week("week"),
}

data class TmdbDiscoveryRowDefinition(
    val key: String,
    val title: String,
    val kind: TmdbDiscoveryRowKind,
    val genreId: String? = null,
    val trendWindow: TmdbTrendWindow = TmdbTrendWindow.Week,
)

/**
 * Stable, launcher-owned catalogue of TMDB discovery rows.
 *
 * Keep the default set compact and familiar. Extra rows stay available from the hidden Movies /
 * Series settings and are fetched only after the user adds them. This lets the catalogue grow
 * without turning every page open into dozens of unnecessary TMDB requests.
 */
object TmdbDiscoveryCatalog {
    fun rows(type: MediaType): List<TmdbDiscoveryRowDefinition> = when (type) {
        MediaType.Movie -> MOVIE_ROWS
        MediaType.Series -> SERIES_ROWS
        else -> emptyList()
    }

    fun defaultRowKeys(type: MediaType): List<String> = when (type) {
        MediaType.Movie -> MOVIE_DEFAULT_KEYS
        MediaType.Series -> SERIES_DEFAULT_KEYS
        else -> emptyList()
    }

    fun selectedRows(type: MediaType, selectedKeys: List<String>): List<TmdbDiscoveryRowDefinition> {
        val available = rows(type).associateBy(TmdbDiscoveryRowDefinition::key)
        return selectedKeys.mapNotNull(available::get).distinctBy(TmdbDiscoveryRowDefinition::key)
    }

    private val MOVIE_ROWS = listOf(
        TmdbDiscoveryRowDefinition("movie-trending", "Filme im Trend", TmdbDiscoveryRowKind.Trending),
        TmdbDiscoveryRowDefinition("movie-popular", "Beliebte Filme", TmdbDiscoveryRowKind.Popular),
        TmdbDiscoveryRowDefinition("movie-top-rated", "Top bewertete Filme", TmdbDiscoveryRowKind.TopRated),
        TmdbDiscoveryRowDefinition("movie-genre-28", "Action", TmdbDiscoveryRowKind.Genre, "28"),
        TmdbDiscoveryRowDefinition("movie-genre-35", "Komödien", TmdbDiscoveryRowKind.Genre, "35"),
        TmdbDiscoveryRowDefinition("movie-genre-878", "Science-Fiction", TmdbDiscoveryRowKind.Genre, "878"),
        TmdbDiscoveryRowDefinition("movie-genre-53", "Thriller", TmdbDiscoveryRowKind.Genre, "53"),
        TmdbDiscoveryRowDefinition("movie-genre-10751", "Familie", TmdbDiscoveryRowKind.Genre, "10751"),

        TmdbDiscoveryRowDefinition(
            "movie-trending-day",
            "Heute im Trend",
            TmdbDiscoveryRowKind.Trending,
            trendWindow = TmdbTrendWindow.Day,
        ),
        TmdbDiscoveryRowDefinition("movie-now-playing", "Jetzt im Kino", TmdbDiscoveryRowKind.NowPlaying),
        TmdbDiscoveryRowDefinition("movie-upcoming", "Demnächst im Kino", TmdbDiscoveryRowKind.Upcoming),
        TmdbDiscoveryRowDefinition("movie-genre-12", "Abenteuer", TmdbDiscoveryRowKind.Genre, "12"),
        TmdbDiscoveryRowDefinition("movie-genre-16", "Animation", TmdbDiscoveryRowKind.Genre, "16"),
        TmdbDiscoveryRowDefinition("movie-genre-80", "Krimi", TmdbDiscoveryRowKind.Genre, "80"),
        TmdbDiscoveryRowDefinition("movie-genre-99", "Dokumentationen", TmdbDiscoveryRowKind.Genre, "99"),
        TmdbDiscoveryRowDefinition("movie-genre-18", "Drama", TmdbDiscoveryRowKind.Genre, "18"),
        TmdbDiscoveryRowDefinition("movie-genre-14", "Fantasy", TmdbDiscoveryRowKind.Genre, "14"),
        TmdbDiscoveryRowDefinition("movie-genre-36", "Historie", TmdbDiscoveryRowKind.Genre, "36"),
        TmdbDiscoveryRowDefinition("movie-genre-27", "Horror", TmdbDiscoveryRowKind.Genre, "27"),
        TmdbDiscoveryRowDefinition("movie-genre-10402", "Musik", TmdbDiscoveryRowKind.Genre, "10402"),
        TmdbDiscoveryRowDefinition("movie-genre-9648", "Mystery", TmdbDiscoveryRowKind.Genre, "9648"),
        TmdbDiscoveryRowDefinition("movie-genre-10749", "Romantik", TmdbDiscoveryRowKind.Genre, "10749"),
        TmdbDiscoveryRowDefinition("movie-genre-10770", "TV-Film", TmdbDiscoveryRowKind.Genre, "10770"),
        TmdbDiscoveryRowDefinition("movie-genre-10752", "Krieg", TmdbDiscoveryRowKind.Genre, "10752"),
        TmdbDiscoveryRowDefinition("movie-genre-37", "Western", TmdbDiscoveryRowKind.Genre, "37"),
    )

    private val SERIES_ROWS = listOf(
        TmdbDiscoveryRowDefinition("series-trending", "Serien im Trend", TmdbDiscoveryRowKind.Trending),
        TmdbDiscoveryRowDefinition("series-popular", "Beliebte Serien", TmdbDiscoveryRowKind.Popular),
        TmdbDiscoveryRowDefinition("series-top-rated", "Top bewertete Serien", TmdbDiscoveryRowKind.TopRated),
        TmdbDiscoveryRowDefinition("series-genre-18", "Drama", TmdbDiscoveryRowKind.Genre, "18"),
        TmdbDiscoveryRowDefinition("series-genre-80", "Krimi", TmdbDiscoveryRowKind.Genre, "80"),
        TmdbDiscoveryRowDefinition("series-genre-35", "Komödien", TmdbDiscoveryRowKind.Genre, "35"),
        TmdbDiscoveryRowDefinition("series-genre-10765", "Sci-Fi & Fantasy", TmdbDiscoveryRowKind.Genre, "10765"),
        TmdbDiscoveryRowDefinition("series-genre-99", "Dokumentationen", TmdbDiscoveryRowKind.Genre, "99"),

        TmdbDiscoveryRowDefinition(
            "series-trending-day",
            "Heute im Trend",
            TmdbDiscoveryRowKind.Trending,
            trendWindow = TmdbTrendWindow.Day,
        ),
        TmdbDiscoveryRowDefinition("series-airing-today", "Heute im TV", TmdbDiscoveryRowKind.AiringToday),
        TmdbDiscoveryRowDefinition("series-on-the-air", "Aktuell im TV", TmdbDiscoveryRowKind.OnTheAir),
        TmdbDiscoveryRowDefinition("series-genre-10759", "Action & Abenteuer", TmdbDiscoveryRowKind.Genre, "10759"),
        TmdbDiscoveryRowDefinition("series-genre-16", "Animation", TmdbDiscoveryRowKind.Genre, "16"),
        TmdbDiscoveryRowDefinition("series-genre-10751", "Familie", TmdbDiscoveryRowKind.Genre, "10751"),
        TmdbDiscoveryRowDefinition("series-genre-10762", "Kinder", TmdbDiscoveryRowKind.Genre, "10762"),
        TmdbDiscoveryRowDefinition("series-genre-9648", "Mystery", TmdbDiscoveryRowKind.Genre, "9648"),
        TmdbDiscoveryRowDefinition("series-genre-10763", "Nachrichten", TmdbDiscoveryRowKind.Genre, "10763"),
        TmdbDiscoveryRowDefinition("series-genre-10764", "Reality", TmdbDiscoveryRowKind.Genre, "10764"),
        TmdbDiscoveryRowDefinition("series-genre-10766", "Soap", TmdbDiscoveryRowKind.Genre, "10766"),
        TmdbDiscoveryRowDefinition("series-genre-10767", "Talk", TmdbDiscoveryRowKind.Genre, "10767"),
        TmdbDiscoveryRowDefinition("series-genre-10768", "Krieg & Politik", TmdbDiscoveryRowKind.Genre, "10768"),
        TmdbDiscoveryRowDefinition("series-genre-37", "Western", TmdbDiscoveryRowKind.Genre, "37"),
    )

    private val MOVIE_DEFAULT_KEYS = listOf(
        "movie-trending",
        "movie-popular",
        "movie-top-rated",
        "movie-genre-28",
        "movie-genre-35",
        "movie-genre-878",
        "movie-genre-53",
        "movie-genre-10751",
    )

    private val SERIES_DEFAULT_KEYS = listOf(
        "series-trending",
        "series-popular",
        "series-top-rated",
        "series-genre-18",
        "series-genre-80",
        "series-genre-35",
        "series-genre-10765",
        "series-genre-99",
    )
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

    private val _hideAnime = MutableStateFlow(
        preferences.getBoolean(KEY_HIDE_ANIME, DEFAULT_HIDE_ANIME),
    )
    val hideAnime: StateFlow<Boolean> = _hideAnime.asStateFlow()

    private val _kidsMode = MutableStateFlow(
        preferences.getBoolean(KEY_KIDS_MODE, DEFAULT_KIDS_MODE),
    )
    val kidsMode: StateFlow<Boolean> = _kidsMode.asStateFlow()

    fun setVisible(type: MediaType, key: String, visible: Boolean) {
        val available = TmdbDiscoveryCatalog.rows(type).map(TmdbDiscoveryRowDefinition::key)
        if (key !in available) return
        val current = current(type)
            .filter { it in available }
            .ifEmpty { TmdbDiscoveryCatalog.defaultRowKeys(type) }
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
        state(type).value = TmdbDiscoveryCatalog.defaultRowKeys(type)
    }

    fun setHideAnime(hidden: Boolean) {
        preferences.edit().putBoolean(KEY_HIDE_ANIME, hidden).apply()
        _hideAnime.value = hidden
    }

    fun setKidsMode(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_KIDS_MODE, enabled).apply()
        _kidsMode.value = enabled
    }

    /** Reads the shared preference directly so repository instances always see the latest value. */
    fun filterSettings(): TmdbDiscoveryFilterSettings = TmdbDiscoveryFilterSettings(
        hideAnime = preferences.getBoolean(KEY_HIDE_ANIME, DEFAULT_HIDE_ANIME),
        kidsMode = preferences.getBoolean(KEY_KIDS_MODE, DEFAULT_KIDS_MODE),
    )

    private fun load(type: MediaType): List<String> {
        val available = TmdbDiscoveryCatalog.rows(type).map(TmdbDiscoveryRowDefinition::key)
        val defaults = TmdbDiscoveryCatalog.defaultRowKeys(type)
        if (!preferences.contains(storageKey(type))) return defaults
        val selected = decode(preferences.getString(storageKey(type), null))
            .filter { it in available }
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
        const val KEY_HIDE_ANIME = "hide_anime"
        const val KEY_KIDS_MODE = "kids_mode"
        const val DEFAULT_HIDE_ANIME = true
        const val DEFAULT_KIDS_MODE = false
        const val SEPARATOR = "\u001F"

        fun encode(items: List<String>): String = items.joinToString(SEPARATOR)
        fun decode(raw: String?): List<String> = raw
            ?.split(SEPARATOR)
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
    }
}
