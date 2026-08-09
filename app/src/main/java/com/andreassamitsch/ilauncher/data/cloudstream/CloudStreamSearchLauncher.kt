package com.andreassamitsch.ilauncher.data.cloudstream

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

class CloudStreamSearchLauncher(private val context: Context) {
    fun isAvailable(): Boolean = resolvePackage(query = "test") != null

    fun launch(query: String): Boolean {
        val packageName = resolvePackage(query) ?: return false
        val intent = searchIntent(query)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun resolvePackage(query: String): String? {
        val packageManager = context.packageManager

        val discovered = packageManager
            .queryIntentActivities(searchIntent(query), PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { it.activityInfo.packageName }
            .firstOrNull { it == BASE_PACKAGE || it.startsWith("$BASE_PACKAGE.") }
        if (discovered != null) return discovered

        return PACKAGE_CANDIDATES.firstOrNull { packageName ->
            searchIntent(query).setPackage(packageName).resolveActivity(packageManager) != null
        }
    }

    private fun searchIntent(query: String): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("$SEARCH_SCHEME://${Uri.encode(query)}"),
    )

    private companion object {
        const val SEARCH_SCHEME = "cloudstreamsearch"
        const val BASE_PACKAGE = "com.lagradost.cloudstream3"
        val PACKAGE_CANDIDATES = listOf(
            BASE_PACKAGE,
            "$BASE_PACKAGE.prerelease",
            "$BASE_PACKAGE.debug",
            "$BASE_PACKAGE.prerelease.debug",
        )
    }
}
