package com.andreassamitsch.servusprovider.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServusInitializeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ServusRefreshWorker.schedule(context.applicationContext)
        ServusRefreshWorker.enqueueNow(context.applicationContext)
    }
}
