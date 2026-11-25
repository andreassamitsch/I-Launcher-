package com.lagradost.cloudstream3

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.utils.UIHelper.navigate

/** Setup is distinct from an installed provider that is not currently available. */
object ILauncherBridgeSetup {
    fun showNoProvidersDialog(activity: FragmentActivity) {
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle("Keine CloudStream-Erweiterungen")
            .setMessage("In dieser CloudStream-App sind keine Erweiterungen installiert. Öffne die Erweiterungsverwaltung, um Provider hinzuzufügen.")
            .setPositiveButton("Erweiterungen") { _, _ ->
                activity.navigate(R.id.navigation_settings_extensions)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    fun showProviderUnavailableDialog(activity: FragmentActivity, provider: String?, retry: () -> Unit) {
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle("Provider nicht verfügbar")
            .setMessage(
                if (provider != null) "Der benötigte Provider ($provider) konnte noch nicht geladen werden oder ist nicht aktiv. Deine Erweiterungen müssen deshalb nicht neu installiert werden."
                else "CloudStream konnte aktuell keine aktiven Provider laden. Prüfe bei Bedarf die Erweiterungen oder versuche es erneut."
            )
            .setPositiveButton("Erneut versuchen") { _, _ -> retry() }
            .setNeutralButton("Erweiterungen") { _, _ ->
                activity.navigate(R.id.navigation_settings_extensions)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
