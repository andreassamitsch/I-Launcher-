package com.andreassamitsch.joyntv

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative stop signal and per-run summary log for the interactive Nord OpenVPN scan. */
internal object JoynNordOpenVpnScanControl {
    private val stopRequested = AtomicBoolean(false)
    private val entries = CopyOnWriteArrayList<String>()
    @Volatile private var appContext: Context? = null

    fun begin(context: Context) {
        appContext = context.applicationContext
        stopRequested.set(false)
        entries.clear()
    }

    fun requestStop() {
        stopRequested.set(true)
        appContext?.let { context ->
            runCatching { JoynNordOpenVpnService.disconnect(context) }
        }
    }

    fun isStopRequested(): Boolean = stopRequested.get()

    fun record(message: String) {
        entries += message.replace('\n', ' ').replace('\r', ' ').trim()
    }

    fun snapshot(): List<String> = entries.toList()

    fun finish() {
        appContext = null
    }
}
