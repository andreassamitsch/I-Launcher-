package com.andreassamitsch.ilauncher.data.apps

import java.util.Locale

internal data class AppCandidate(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isTvApp: Boolean,
)

internal object AppCatalogPolicy {
    fun select(
        candidates: List<AppCandidate>,
        ownPackageName: String,
    ): List<AppCandidate> {
        val preferredPerPackage = candidates
            .asSequence()
            .filter { it.packageName != ownPackageName }
            .sortedWith(
                compareByDescending<AppCandidate> { it.isTvApp }
                    .thenBy { it.label.lowercase(Locale.ROOT) }
                    .thenBy { it.activityName },
            )
            .distinctBy { it.packageName }
            .toList()

        return preferredPerPackage.sortedWith(
            compareBy<AppCandidate> { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.packageName },
        )
    }
}
