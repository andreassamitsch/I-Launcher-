package com.andreassamitsch.servusprovider.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andreassamitsch.servusprovider.data.ServusNewsStore
import com.andreassamitsch.servusprovider.tv.ServusChannelPublisher

class ServusInitializeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext

        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Branding URIs are persisted in Android TvProvider rows. Re-publish cached programs
            // immediately after an app update so migrations (for example android.resource:// to
            // cross-process content://) do not wait for a new episode or catalogue change.
            runCatching {
                val episodes = ServusNewsStore(appContext).loadEpisodes()
                if (episodes.isNotEmpty()) {
                    ServusChannelPublisher(appContext).publish(episodes)
                }
            }
        }

        ServusRefreshWorker.schedule(appContext)
        ServusRefreshWorker.enqueueNow(appContext)
    }
}
