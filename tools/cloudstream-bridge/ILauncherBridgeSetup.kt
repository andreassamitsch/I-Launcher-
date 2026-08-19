package com.lagradost.cloudstream3

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.utils.UIHelper.navigate

/**
 * Visible setup diagnostics for the I Launcher bridge build.
 *
 * The bridge APK uses its own Android package and therefore its own app-private CloudStream data.
 * Extensions/repositories installed in another CloudStream package cannot be read across Android's
 * application sandbox. Keep this message in the CloudStream process where that distinction is true.
 */
object ILauncherBridgeSetup {
    fun showNoProvidersDialog(activity: FragmentActivity) {
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle("Keine CloudStream-Provider")
            .setMessage(
                "Die I-Launcher-CloudStream-Bridge ist eine separate CloudStream-App. " +
                    "Installiere bzw. aktiviere deine Repositories und Erweiterungen auch in " +
                    "dieser Dev-App. Erweiterungen der offiziellen CloudStream-App können wegen " +
                    "der Android-App-Isolation nicht automatisch übernommen werden.",
            )
            .setPositiveButton("Erweiterungen") { _, _ ->
                activity.navigate(R.id.navigation_settings_extensions)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
