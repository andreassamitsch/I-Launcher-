package com.andreassamitsch.ilauncher.data.tv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * TvProvider change notifications are not guaranteed to cover every playback hand-off on every TV.
 * Re-read provider-backed rows whenever one of I Launcher's activities resumes so progress written
 * by the source app while I Launcher was in the background is visible immediately on return.
 */
internal fun registerTvProviderResumeRefresh(
    context: Context,
    onResume: () -> Unit,
): () -> Unit {
    val application = context.applicationContext as? Application ?: return {}
    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = onResume()
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    application.registerActivityLifecycleCallbacks(callbacks)
    return { application.unregisterActivityLifecycleCallbacks(callbacks) }
}
