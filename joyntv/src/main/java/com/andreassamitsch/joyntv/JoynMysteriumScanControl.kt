package com.andreassamitsch.joyntv

import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative stop flag for the residential-IP scan. */
internal object JoynMysteriumScanControl {
    private val stopRequested = AtomicBoolean(false)

    fun reset() {
        stopRequested.set(false)
    }

    fun requestStop() {
        stopRequested.set(true)
    }

    fun isStopRequested(): Boolean = stopRequested.get()
}
