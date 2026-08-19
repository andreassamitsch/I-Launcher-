package com.lagradost.cloudstream3

import android.content.Context
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings

/** CloudStream-owned settings used only by the I Launcher bridge build. */
internal object ILauncherBridgePreferences {
    private const val PREFS_NAME = "i_launcher_direct_play"
    private const val PREF_PROVIDER_ORDER_LEGACY = "provider_order"
    private const val PREF_PROVIDER_ORDER_MOVIE = "provider_order_movie"
    private const val PREF_PROVIDER_ORDER_SERIES = "provider_order_series"
    private const val PREF_AUTO_UPDATE = "bridge_auto_update"
    private const val PROVIDER_SEPARATOR = "\u001F"

    fun install(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext()
        migrateLegacyOrder(context)
        val screen = fragment.preferenceScreen ?: return

        val category = PreferenceCategory(context).apply {
            key = "i_launcher_bridge_category"
            title = "I Launcher Bridge"
            summary = "Direktwiedergabe, Provider-Prioritäten und Development-Updates"
        }
        screen.addPreference(category)

        fun addPriority(kind: ILauncherDirectPlay.MediaKind, title: String) {
            val pref = Preference(context).apply {
                key = "i_launcher_bridge_priority_${providerOrderKey(kind)}"
                this.title = title
                summary = prioritySummary(context, kind)
                isPersistent = false
                setOnPreferenceClickListener {
                    showPriorityDialog(fragment, kind, this)
                    true
                }
            }
            category.addPreference(pref)
        }

        addPriority(ILauncherDirectPlay.MediaKind.Movie, "Provider-Priorität · Filme")
        addPriority(ILauncherDirectPlay.MediaKind.Series, "Provider-Priorität · Serien")

        category.addPreference(
            SwitchPreferenceCompat(context).apply {
                key = PREF_AUTO_UPDATE
                title = "Bridge-Updates automatisch prüfen"
                summary = "Prüft beim Start dieser CloudStream-Dev-App auf eine neuere I-Launcher-Bridge."
                setDefaultValue(true)
                isChecked = isAutoUpdateEnabled(context)
                setOnPreferenceChangeListener { _, value ->
                    preferences(context).edit().putBoolean(PREF_AUTO_UPDATE, value as Boolean).apply()
                    true
                }
            },
        )

        category.addPreference(
            Preference(context).apply {
                key = "i_launcher_bridge_update_now"
                title = "Bridge-Update jetzt prüfen"
                summary = "Aktuelle Development-APK aus dem I-Launcher-Repository prüfen und installieren."
                isPersistent = false
                setOnPreferenceClickListener {
                    fragment.activity?.let { activity ->
                        ILauncherBridgeUpdater.checkForUpdates(activity, automatic = false)
                    }
                    true
                }
            },
        )
    }

    fun isAutoUpdateEnabled(context: Context): Boolean =
        preferences(context).getBoolean(PREF_AUTO_UPDATE, true)

    fun readProviderOrder(
        context: Context,
        kind: ILauncherDirectPlay.MediaKind,
    ): List<String> {
        migrateLegacyOrder(context)
        return preferences(context).getString(providerOrderKey(kind), null)
            ?.split(PROVIDER_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()
    }

    fun saveProviderOrder(
        context: Context,
        kind: ILauncherDirectPlay.MediaKind,
        order: List<String>,
    ) {
        preferences(context).edit()
            .putString(providerOrderKey(kind), order.filter(String::isNotBlank).distinct().joinToString(PROVIDER_SEPARATOR))
            .apply()
    }

    internal fun providerOrderKey(kind: ILauncherDirectPlay.MediaKind): String = when (kind) {
        ILauncherDirectPlay.MediaKind.Movie -> PREF_PROVIDER_ORDER_MOVIE
        ILauncherDirectPlay.MediaKind.Series,
        ILauncherDirectPlay.MediaKind.Episode,
        ILauncherDirectPlay.MediaKind.Unknown -> PREF_PROVIDER_ORDER_SERIES
    }

    internal fun kindLabel(kind: ILauncherDirectPlay.MediaKind): String = when (kind) {
        ILauncherDirectPlay.MediaKind.Movie -> "Filme"
        else -> "Serien"
    }

    private fun prioritySummary(context: Context, kind: ILauncherDirectPlay.MediaKind): String {
        val active = activeProviderNames(context)
        val ordered = ILauncherDirectPlay.mergeProviderOrder(active, readProviderOrder(context, kind))
        return if (ordered.isEmpty()) "Keine aktiven Provider" else ordered.joinToString("  ›  ")
    }

    private fun showPriorityDialog(
        fragment: PreferenceFragmentCompat,
        kind: ILauncherDirectPlay.MediaKind,
        preference: Preference,
    ) {
        val context = fragment.requireContext()
        val activeNames = activeProviderNames(context)
        if (activeNames.isEmpty()) {
            AlertDialog.Builder(context, R.style.AlertDialogCustom)
                .setTitle("Provider-Priorität · ${kindLabel(kind)}")
                .setMessage("Keine aktiven CloudStream-Provider gefunden.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val ordered = ILauncherDirectPlay.mergeProviderOrder(
            activeNames,
            readProviderOrder(context, kind),
        ).toMutableList()
        var selected = 0
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, ordered)
        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle("Provider-Priorität · ${kindLabel(kind)}")
            .setSingleChoiceItems(adapter, selected) { _, which -> selected = which }
            .setNeutralButton("Nach oben", null)
            .setNegativeButton("Nach unten", null)
            .setPositiveButton("Fertig") { _, _ ->
                saveProviderOrder(context, kind, ordered)
                preference.summary = prioritySummary(context, kind)
            }
            .create()

        dialog.setOnShowListener {
            fun refreshSelection() {
                adapter.notifyDataSetChanged()
                dialog.listView.setItemChecked(selected, true)
                dialog.listView.setSelection(selected)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (selected > 0) {
                    val item = ordered.removeAt(selected)
                    selected -= 1
                    ordered.add(selected, item)
                    refreshSelection()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (selected < ordered.lastIndex) {
                    val item = ordered.removeAt(selected)
                    selected += 1
                    ordered.add(selected, item)
                    refreshSelection()
                }
            }
            refreshSelection()
            dialog.listView.requestFocus()
        }
        dialog.show()
    }

    private fun activeProviderNames(context: Context): List<String> {
        val activeNames = context.getApiSettings()
        return apis.withLock {
            apis.filter { it.name in activeNames }
                .map { it.name }
                .filter(String::isNotBlank)
                .distinct()
        }
    }

    private fun migrateLegacyOrder(context: Context) {
        val prefs = preferences(context)
        val legacy = prefs.getString(PREF_PROVIDER_ORDER_LEGACY, null) ?: return
        val editor = prefs.edit()
        if (!prefs.contains(PREF_PROVIDER_ORDER_MOVIE)) editor.putString(PREF_PROVIDER_ORDER_MOVIE, legacy)
        if (!prefs.contains(PREF_PROVIDER_ORDER_SERIES)) editor.putString(PREF_PROVIDER_ORDER_SERIES, legacy)
        editor.apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
