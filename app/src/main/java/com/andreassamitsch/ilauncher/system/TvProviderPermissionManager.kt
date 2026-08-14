package com.andreassamitsch.ilauncher.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

object TvProviderPermissionManager {
    const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

    private const val PREFS_NAME = "tv_provider_permissions"
    private const val KEY_INITIAL_REQUEST_SHOWN = "read_tv_listings_initial_request_shown"

    fun hasReadTvListings(context: Context): Boolean =
        context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    fun shouldShowInitialRequest(context: Context): Boolean =
        !hasReadTvListings(context) &&
            !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_INITIAL_REQUEST_SHOWN, false)

    fun markInitialRequestShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INITIAL_REQUEST_SHOWN, true)
            .apply()
    }

    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
