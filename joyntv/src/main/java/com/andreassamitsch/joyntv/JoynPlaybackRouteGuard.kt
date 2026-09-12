package com.andreassamitsch.joyntv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * Keeps the residential market selected by the user stable for the complete live-player lifetime.
 *
 * MainActivity can continue its Compose/background work while PlayerActivity is on top. Those jobs
 * must not rotate the process-wide proxy to AT/DE/CH between entitlement, DASH manifest, DRM and
 * segment requests. The actual routing pin lives in [JoynProxySettings]; this guard only ties its
 * lifetime to PlayerActivity without making PlayerActivity itself responsible for proxy internals.
 */
internal object JoynPlaybackRouteGuard {
    private val lock = Any()

    @Volatile
    private var callbacksInstalled = false

    private var activePlayerActivities = 0
    private var pinActive = false
    private var application: Application? = null

    fun pinCurrent(context: Context): Boolean {
        val app = context.applicationContext as? Application ?: return false
        installCallbacks(app)

        val settings = JoynProxySettings(app)
        val config = settings.current()
        if (!config.isUsable || !config.isMysterium) return false
        if (!settings.beginPlaybackRoutePin(config)) return false

        synchronized(lock) {
            application = app
            pinActive = true
        }
        return true
    }

    fun release(context: Context) {
        val shouldRelease = synchronized(lock) {
            if (!pinActive) return@synchronized false
            pinActive = false
            activePlayerActivities = 0
            true
        }
        if (shouldRelease) JoynProxySettings(context.applicationContext).endPlaybackRoutePin()
    }

    private fun installCallbacks(app: Application) {
        synchronized(lock) {
            if (callbacksInstalled) return
            callbacksInstalled = true
            application = app
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity !is PlayerActivity) return
                    synchronized(lock) { activePlayerActivities++ }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity !is PlayerActivity) return
                    val releaseContext = synchronized(lock) {
                        if (activePlayerActivities > 0) activePlayerActivities--
                        if (activePlayerActivities == 0 && pinActive) {
                            pinActive = false
                            application
                        } else {
                            null
                        }
                    }
                    releaseContext?.let { JoynProxySettings(it).endPlaybackRoutePin() }
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            })
        }
    }
}
