package com.andreassamitsch.ilauncher.data.home

import android.content.Context
import com.andreassamitsch.ilauncher.model.InstalledApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _rowOrder = MutableStateFlow(loadList(KEY_ROW_ORDER))
    val rowOrder: StateFlow<List<String>> = _rowOrder.asStateFlow()

    private val _appOrder = MutableStateFlow(loadList(KEY_APP_ORDER))
    val appOrder: StateFlow<List<String>> = _appOrder.asStateFlow()

    fun moveRow(availableKeys: List<String>, key: String, delta: Int) {
        val current = mergeOrder(_rowOrder.value, availableKeys)
        saveRowOrder(move(current, key, delta))
    }

    fun resetRows() {
        preferences.edit().remove(KEY_ROW_ORDER).apply()
        _rowOrder.value = emptyList()
    }

    fun moveApp(availablePackages: List<String>, packageName: String, delta: Int) {
        val current = mergeOrder(_appOrder.value, availablePackages)
        saveAppOrder(move(current, packageName, delta))
    }

    fun resetApps() {
        preferences.edit().remove(KEY_APP_ORDER).apply()
        _appOrder.value = emptyList()
    }

    private fun saveRowOrder(order: List<String>) {
        preferences.edit().putString(KEY_ROW_ORDER, encode(order)).apply()
        _rowOrder.value = order
    }

    private fun saveAppOrder(order: List<String>) {
        preferences.edit().putString(KEY_APP_ORDER, encode(order)).apply()
        _appOrder.value = order
    }

    private fun loadList(key: String): List<String> = decode(preferences.getString(key, null))

    companion object {
        const val ROW_WATCH_NEXT = "watch_next"
        const val ROW_LIVE_TV = "live_tv"
        const val ROW_APPS = "apps"
        private const val ROW_PREVIEW_PREFIX = "preview:"
        private const val PREFS_NAME = "home_preferences"
        private const val KEY_ROW_ORDER = "row_order"
        private const val KEY_APP_ORDER = "app_order"
        private const val SEPARATOR = "\u001F"

        fun previewRowKey(channelId: String): String = "$ROW_PREVIEW_PREFIX$channelId"
        fun previewChannelId(rowKey: String): String? =
            rowKey.takeIf { it.startsWith(ROW_PREVIEW_PREFIX) }?.removePrefix(ROW_PREVIEW_PREFIX)

        fun mergeOrder(saved: List<String>, available: List<String>): List<String> {
            val availableSet = available.toSet()
            return buildList {
                saved.forEach { if (it in availableSet && it !in this) add(it) }
                available.forEach { if (it !in this) add(it) }
            }
        }

        fun move(order: List<String>, key: String, delta: Int): List<String> {
            if (delta == 0) return order
            val from = order.indexOf(key)
            if (from < 0) return order
            val to = (from + delta).coerceIn(0, order.lastIndex)
            if (from == to) return order
            return order.toMutableList().apply {
                val item = removeAt(from)
                add(to, item)
            }
        }

        fun orderApps(apps: List<InstalledApp>, saved: List<String>): List<InstalledApp> {
            val byPackage = apps.associateBy(InstalledApp::packageName)
            return buildList {
                saved.mapNotNull(byPackage::get).forEach(::add)
                apps.forEach { if (none { ordered -> ordered.packageName == it.packageName }) add(it) }
            }
        }

        private fun encode(items: List<String>): String = items.joinToString(SEPARATOR)
        private fun decode(raw: String?): List<String> = raw
            ?.split(SEPARATOR)
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
    }
}
