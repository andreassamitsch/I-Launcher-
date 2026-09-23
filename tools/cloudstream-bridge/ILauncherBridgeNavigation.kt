package com.lagradost.cloudstream3

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.ui.player.ILauncherWatchNextSync
import com.lagradost.cloudstream3.utils.Coroutines.main
import java.util.concurrent.atomic.AtomicLong

/**
 * CloudStream's MainActivity is singleTask: a new external VIEW intent reuses the old activity.
 * Remove the whole previous result/player chain, not just the closest player destination.
 * Every external handoff receives a sequence number so an older, slower provider lookup cannot
 * navigate after a newer Watch Next request has already arrived.
 */
internal object ILauncherBridgeNavigation {
    private const val TAG = "ILauncherBridgeNav"
    private val externalLaunchSequence = AtomicLong(0L)

    /** Call on the activity/main thread as soon as a valid external playback intent arrives. */
    fun beginExternalLaunch(activity: FragmentActivity): Long {
        val token = externalLaunchSequence.incrementAndGet()
        navController(activity)?.let(::resetToHome)
        return token
    }

    fun isCurrentExternalLaunch(token: Long): Boolean =
        externalLaunchSequence.get() == token

    fun replacePlayer(activity: FragmentActivity, args: Bundle, token: Long? = null) {
        main {
            if (token != null && !isCurrentExternalLaunch(token)) return@main
            val navController = navController(activity) ?: return@main
            if (token != null) resetToHome(navController) else clearExistingPlayer(navController)
            if (token != null && !isCurrentExternalLaunch(token)) return@main
            navController.navigate(R.id.global_to_navigation_player, args)
        }
    }

    fun clearExistingPlayer(activity: FragmentActivity): Boolean {
        val navController = navController(activity) ?: return false
        return clearExistingPlayer(navController)
    }

    private fun resetToHome(navController: NavController) {
        if (navController.currentDestination?.id == R.id.navigation_home) return
        // Old player onStop/onDestroy must not overwrite the new target's resume pointer.
        ILauncherWatchNextSync.beginInternalHandoff()
        val popped = runCatching {
            navController.popBackStack(R.id.navigation_home, false)
        }.getOrDefault(false)
        if (popped || navController.currentDestination?.id == R.id.navigation_home) return

        // The saved stack might not contain home (e.g. a restored deep-link task). Build a new
        // home-rooted stack instead of preserving an unrelated previous episode underneath.
        runCatching {
            navController.navigate(
                R.id.navigation_home,
                null,
                NavOptions.Builder()
                    .setPopUpTo(navController.graph.id, true)
                    .setLaunchSingleTop(true)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Could not reset external launch to home", it) }
    }

    private fun clearExistingPlayer(navController: NavController): Boolean {
        ILauncherWatchNextSync.beginInternalHandoff()
        var removedAny = false
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
