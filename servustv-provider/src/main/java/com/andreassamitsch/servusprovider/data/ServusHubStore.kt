package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.google.gson.reflect.TypeToken

class ServusHubStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val newsStore = ServusNewsStore(appContext)
    private val categoryListType = object : TypeToken<List<ServusCategory>>() {}.type
    private val liveListType = object : TypeToken<List<ServusLiveChannel>>() {}.type

    fun loadCategories(): List<ServusCategory> {
        // Gson cannot safely apply Kotlin default values to reference-allocated legacy data classes.
        // Drop only the provider metadata cache once when introducing first-class collections; user
        // selections live in separate stores and survive. The next normal refresh rebuilds metadata.
        if (preferences.getInt(KEY_DATA_SCHEMA, 0) < CURRENT_DATA_SCHEMA) {
            preferences.edit()
                .remove(KEY_CATEGORIES)
                .putLong(KEY_CATALOG_SUCCESS, 0L)
                .putInt(KEY_DATA_SCHEMA, CURRENT_DATA_SCHEMA)
                .putInt(KEY_TIME_SCHEMA, CURRENT_TIME_SCHEMA)
                .apply()
            return emptyList()
        }

        val categories = loadList<ServusCategory>(KEY_CATEGORIES, categoryListType)
        if (categories.isEmpty()) return categories

        if (preferences.getInt(KEY_TIME_SCHEMA, 1) < CURRENT_TIME_SCHEMA) {
            val migrated = canonicalizeCategories(
                categories.map { category ->
                    category.copy(
                        shows = category.shows.map { show ->
                            show.copy(episodes = emptyList(), collections = emptyList())
                        },
                    )
                },
            )
            preferences.edit()
                .putString(KEY_CATEGORIES, ServusNetwork.gson.toJson(migrated))
                .putLong(KEY_CATALOG_SUCCESS, 0L)
                .putInt(KEY_TIME_SCHEMA, CURRENT_TIME_SCHEMA)
                .putInt(KEY_DATA_SCHEMA, CURRENT_DATA_SCHEMA)
                .apply()
            return migrated
        }

        val canonical = canonicalizeCategories(categories)
        if (canonical != categories) {
            preferences.edit()
                .putString(KEY_CATEGORIES, ServusNetwork.gson.toJson(canonical))
                .apply()
        }
        return canonical
    }

    fun loadLiveChannels(): List<ServusLiveChannel> = loadList(KEY_LIVE_CHANNELS, liveListType)

    fun saveCatalog(categories: List<ServusCategory>, refreshedAtMillis: Long) {
        val canonical = canonicalizeCategories(categories)
        preferences.edit()
            .putString(KEY_CATEGORIES, ServusNetwork.gson.toJson(canonical))
            .putLong(KEY_CATALOG_SUCCESS, refreshedAtMillis)
            .putInt(KEY_TIME_SCHEMA, CURRENT_TIME_SCHEMA)
            .putInt(KEY_DATA_SCHEMA, CURRENT_DATA_SCHEMA)
            .apply()
    }

    fun saveCatalogContent(categories: List<ServusCategory>) {
        val canonical = canonicalizeCategories(categories)
        preferences.edit()
            .putString(KEY_CATEGORIES, ServusNetwork.gson.toJson(canonical))
            .putInt(KEY_TIME_SCHEMA, CURRENT_TIME_SCHEMA)
            .putInt(KEY_DATA_SCHEMA, CURRENT_DATA_SCHEMA)
            .apply()
    }

    fun updateShowLogo(showId: String, logoUri: String) {
        val resolved = ServusBranding.logoUriForShow(showId, logoUri) ?: return
        val categories = loadCategories()
        var changed = false
        val updated = categories.map { category ->
            category.copy(
                shows = category.shows.map { show ->
                    if (show.id != showId || show.logoUri == resolved) {
                        show
                    } else {
                        changed = true
                        show.copy(logoUri = resolved)
                    }
                },
            )
        }
        if (changed) saveCatalogContent(updated)
    }

    fun saveCatalogDiagnostic(message: String) {
        preferences.edit()
            .putString(KEY_CATALOG_DIAGNOSTIC, ServusCatalogDiagnosticBuilder.sanitize(message))
            .apply()
    }

    fun catalogDiagnostic(): String? = preferences.getString(KEY_CATALOG_DIAGNOSTIC, null)
        ?.takeIf { it.isNotBlank() }

    fun saveLiveChannels(channels: List<ServusLiveChannel>, refreshedAtMillis: Long) {
        preferences.edit()
            .putString(KEY_LIVE_CHANNELS, ServusNetwork.gson.toJson(channels))
            .putLong(KEY_LIVE_SUCCESS, refreshedAtMillis)
            .apply()
    }

    fun catalogLastSuccessMillis(): Long = preferences.getLong(KEY_CATALOG_SUCCESS, 0L)

    fun liveLastSuccessMillis(): Long = preferences.getLong(KEY_LIVE_SUCCESS, 0L)

    fun findShow(showId: String): ServusShow? = loadCategories()
        .asSequence()
        .flatMap { it.shows.asSequence() }
        .firstOrNull { it.id == showId }

    fun findLiveChannel(channelId: String): ServusLiveChannel? = loadLiveChannels()
        .firstOrNull { it.id == channelId }

    private fun canonicalizeCategories(categories: List<ServusCategory>): List<ServusCategory> {
        val canonical = categories.map { category ->
            category.copy(
                shows = category.shows.map { show ->
                    val canonicalEpisodes = show.episodes.map(ServusBranding::canonicalizeEpisode)
                    show.copy(
                        logoUri = ServusBranding.catalogueLogoUriForShow(show.id, show.logoUri),
                        episodes = canonicalEpisodes,
                        collections = show.collections.map { collection ->
                            collection.copy(
                                episodes = collection.episodes.map(ServusBranding::canonicalizeEpisode),
                            )
                        },
                    )
                },
            )
        }
        return ServusCatalogAugmentation.withEditorialShows(
            categories = canonical,
            currentEpisodes = newsStore.loadEpisodes(),
        )
    }

    private fun <T> loadList(key: String, type: java.lang.reflect.Type): List<T> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            ServusNetwork.gson.fromJson<List<T>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_NAME = "servus_hub"
        const val KEY_CATEGORIES = "categories"
        const val KEY_LIVE_CHANNELS = "live_channels"
        const val KEY_CATALOG_SUCCESS = "catalog_success"
        const val KEY_LIVE_SUCCESS = "live_success"
        const val KEY_CATALOG_DIAGNOSTIC = "catalog_diagnostic"
        const val KEY_TIME_SCHEMA = "availability_time_schema"
        const val KEY_DATA_SCHEMA = "hub_data_schema"
        const val CURRENT_TIME_SCHEMA = 2
        const val CURRENT_DATA_SCHEMA = 3
    }
}
