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
 * MainActivity is singleTask, so a launcher handoff can arrive while one or more old players are
 * still present in CloudStream's navigation stack. Remove every player destination before opening
 * the newly requested episode. The Watch Next sync helper suppresses resume-pointer mutation while
 * removed players receive their lifecycle callbacks.
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
        var removedAny = false

        // popBackStack(destination, inclusive=true) removes only the nearest matching destination.
        // Repeat until no GeneratorPlayer remains, otherwise a sufficiently deep Back sequence could
        // still uncover an even older playback session.
        while (true) {
            val removed = runCatching {
                navController.popBackStack(R.id.navigation_player, true)
            }.getOrDefault(false)
            if (!removed) break
            removedAny = true
        }

        if (!removedAny) ILauncherWatchNextSync.cancelInternalHandoff()
        return removedAny
    }

    private fun navController(activity: FragmentActivity): NavController? =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)
            ?.navController
}
