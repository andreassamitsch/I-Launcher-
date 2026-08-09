package com.andreassamitsch.ilauncher.data.cloudstream

import android.content.Context
import android.content.Intent
import android.net.Uri

class CloudStreamSearchLauncher(private val context: Context) {
    fun isAvailable(): Boolean = resolvePackage(query = "test") != null

    fun launch(query: String): Boolean {
        val packageName = resolvePackage(query) ?: return false
        val intent = searchIntent(packageName, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun resolvePackage(query: String): String? = PACKAGE_CANDIDATES.firstOrNull { packageName ->
        searchIntent(packageName, query).resolveActivity(context.packageManager) != null
    }

    private fun searchIntent(packageName: String, query: String): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("$SEARCH_SCHEME://${Uri.encode(query)}"),
    ).setPackage(packageName)

    private companion object {
        const val SEARCH_SCHEME = "cloudstreamsearch"
        val PACKAGE_CANDIDATES = listOf(
            "com.lagradost.cloudstream3",
            "com.lagradost.cloudstream3.prerelease",
            "com.lagradost.cloudstream3.debug",
        )
    }
}
