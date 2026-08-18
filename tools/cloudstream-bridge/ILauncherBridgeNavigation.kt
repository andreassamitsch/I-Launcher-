package com.lagradost.cloudstream3

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.ui.player.ILauncherWatchNextSync
import com.lagradost.cloudstream3.utils.Coroutines.main

/**
 * Keeps externally requested playback from stacking on top of an old GeneratorPlayer.
 *
 * MainActivity is singleTask, so a launcher handoff can arrive while an old player is still the
 * current destination or deeper in CloudStream's navigation stack. Pop that player inclusively
 * before opening the newly requested episode. The Watch Next sync helper suppresses resume-pointer
 * mutation while the removed player receives its lifecycle callbacks.
 */
internal object ILauncherBridgeNavigation {
    fun replacePlayer(activity: FragmentActivity, args: Bundle) {
        main {
            val navController = navController(activity) ?: return@main
            clearExistingPlayer(navController)
            navController.navigate(R.id.global_to_navigation_player, args)
        }
    }

    fun clearExistingPlayer(activity: FragmentActivity): Boolean {
        val navController = navController(activity) ?: return false
        return clearExistingPlayer(navController)
    }

    private fun clearExistingPlayer(navController: NavController): Boolean {
        ILauncherWatchNextSync.beginInternalHandoff()
        val removed = runCatching {
            navController.popBackStack(R.id.navigation_player, true)
        }.getOrDefault(false)
        if (!removed) ILauncherWatchNextSync.cancelInternalHandoff()
        return removed
    }

    private fun navController(activity: FragmentActivity): NavController? =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)
            ?.navController
}
