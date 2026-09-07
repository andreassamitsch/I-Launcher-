package com.andreassamitsch.servusprovider.data

import android.content.Context

/** Local opt-in for Android-TV Preview Channels backed by a whole show or one content collection. */
class ServusShowChannelSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectedSourceKeys(): Set<String> =
        preferences.getStringSet(KEY_SELECTED_SOURCE_KEYS, emptySet()).orEmpty().toSet()

    fun effectiveSelectedSourceKeys(categories: List<ServusCategory>): Set<String> {
        val valid = ServusSourceKey.validKeys(categories)
        return selectedSourceKeys().filterTo(linkedSetOf()) { it in valid }
    }

    fun effectiveSelectedShowIds(categories: List<ServusCategory>): Set<String> =
        effectiveSelectedSourceKeys(categories)
            .filterNotTo(linkedSetOf(), ServusSourceKey::isCollection)

    fun selectedCollectionParentShowIds(categories: List<ServusCategory>): Set<String> =
        ServusSourceKey.collectionParentShowIds(effectiveSelectedSourceKeys(categories))

    fun selectedCollectionIdsForShow(showId: String, categories: List<ServusCategory>): Set<String> =
        effectiveSelectedSourceKeys(categories).asSequence()
            .filter(ServusSourceKey::isCollection)
            .filter { ServusSourceKey.parentShowId(it) == showId }
            .mapNotNull(ServusSourceKey::collectionId)
            .toCollection(linkedSetOf())

    fun isSelected(showId: String): Boolean = ServusSourceKey.show(showId) in selectedSourceKeys()

    fun isCollectionSelected(showId: String, collectionId: String): Boolean =
        ServusSourceKey.collection(showId, collectionId) in selectedSourceKeys()

    fun needsTvProviderSync(): Boolean =
        !preferences.getBoolean(KEY_SYNC_INITIALIZED, false) ||
            preferences.getBoolean(KEY_SYNC_PENDING, false)

    fun setSelected(showId: String, selected: Boolean) {
        setSourceSelected(ServusSourceKey.show(showId), selected)
    }

    fun setCollectionSelected(showId: String, collectionId: String, selected: Boolean) {
        setSourceSelected(ServusSourceKey.collection(showId, collectionId), selected)
    }

    private fun setSourceSelected(key: String, selected: Boolean) {
        val keys = selectedSourceKeys().toMutableSet()
        val changed = if (selected) keys.add(key) else keys.remove(key)
        if (!changed) return
        preferences.edit()
            .putStringSet(KEY_SELECTED_SOURCE_KEYS, keys)
            .putBoolean(KEY_SYNC_PENDING, true)
            .apply()
    }

    fun markTvProviderSynced() {
        preferences.edit()
            .putBoolean(KEY_SYNC_INITIALIZED, true)
            .putBoolean(KEY_SYNC_PENDING, false)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "servus_show_channel_selection"
        // Existing values are plain show IDs and remain valid source keys.
        const val KEY_SELECTED_SOURCE_KEYS = "selected_show_ids"
        const val KEY_SYNC_INITIALIZED = "tv_provider_sync_initialized"
        const val KEY_SYNC_PENDING = "tv_provider_sync_pending"
    }
}
