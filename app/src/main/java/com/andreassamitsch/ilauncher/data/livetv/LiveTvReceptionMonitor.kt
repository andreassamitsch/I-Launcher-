package com.andreassamitsch.ilauncher.data.livetv

import android.content.Context
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifSignalReader
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifSignalStatus
import com.andreassamitsch.ilauncher.data.oscam.OscamReadResult
import com.andreassamitsch.ilauncher.data.oscam.OscamStatusReader
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Background health sampling for the active SAT service.
 *
 * This deliberately lives outside the visible player overlay. The overlay disappears after a few
 * seconds, while automatic SAT -> Joyn fallback must continue observing RF quality and OSCam for
 * the entire channel session.
 */
internal class LiveTvReceptionMonitor(context: Context) {
    private val signalReader = OpenWebifSignalReader(context.applicationContext)
    private val oscamReader = OscamStatusReader(context.applicationContext)
    private var lastRealignAtMillis = 0L

    suspend fun sample(channel: LiveTvChannel): LiveTvReceptionSnapshot = coroutineScope {
        val oscam = async { oscamReader.read(channel.serviceReference) }
        var signalResult = signalReader.read()
        var signal = signalResult.getOrNull()

        if (signal != null && !signal.hasMeasurements) {
            val now = System.currentTimeMillis()
            val canRealign = lastRealignAtMillis == 0L ||
                now - lastRealignAtMillis >= REALIGN_COOLDOWN_MILLIS
            if (canRealign) {
                lastRealignAtMillis = now
                if (
                    signalReader.alignCurrentService(
                        serviceReference = channel.serviceReference,
                        title = channel.name,
                    ).getOrDefault(false)
                ) {
                    delay(REALIGN_RECHECK_MILLIS)
                    signalResult = signalReader.read()
                    signal = signalResult.getOrNull()
                }
            }
        }

        LiveTvReceptionSnapshot(
            signal = signal?.takeIf { it.hasMeasurements },
            signalUnavailable = signalResult.isFailure || signal?.hasMeasurements != true,
            oscam = oscam.await(),
            sampledAtEpochMillis = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val REALIGN_RECHECK_MILLIS = 250L
        private const val REALIGN_COOLDOWN_MILLIS = 2_500L
    }
}

internal data class LiveTvReceptionSnapshot(
    val signal: OpenWebifSignalStatus?,
    val signalUnavailable: Boolean,
    val oscam: OscamReadResult?,
    val sampledAtEpochMillis: Long,
) {
    val hasBitErrors: Boolean
        get() {
            val raw = signal?.ber?.trim().orEmpty()
            if (raw.isBlank()) return false
            val number = Regex("[-+]?\\d+(?:[.,]\\d+)?")
                .find(raw)
                ?.value
                ?.replace(',', '.')
                ?.toDoubleOrNull()
            return number != null && number > 0.0
        }

    val hasFreshOscamFailure: Boolean
        get() = (oscam as? OscamReadResult.Match)?.status?.let { it.fresh && it.failed } == true
}

internal enum class LiveTvSatFailureReason(val overlayText: String) {
    LOW_SNR("SAT-Signal zu schwach"),
    BIT_ERRORS("SAT-Signal stark gestört"),
    OSCAM("OSCam-Entschlüsselung fehlgeschlagen"),
    BUFFERING("SAT-Stream puffert zu lange"),
    PLAYBACK("SAT-Wiedergabe fehlgeschlagen"),
    RECEIVER("Gigablue nicht erreichbar"),
}

/**
 * Conservative health policy for an optional SAT -> Joyn fallback.
 *
 * SAT is the preferred source. RF telemetry alone must therefore not cause a source switch for one
 * weak SNR value or a few isolated BER ticks. Automatic RF fallback is only requested when both a
 * very weak real SNR and bit errors persist together for several seconds. Actual Media3 buffering
 * and fatal playback failures are handled separately by the player.
 *
 * OSCam failures use a continuous five-second window from the first fresh failed ECM. This gives
 * slow encrypted services enough time to obtain a valid control word. One healthy/non-failed ECM
 * resets the window completely.
 */
internal class LiveTvSatHealthPolicy {
    private var severeRfSamples = 0
    private var oscamFailureSinceEpochMillis: Long? = null

    fun update(snapshot: LiveTvReceptionSnapshot): LiveTvSatFailureReason? {
        val severeRfFailure =
            snapshot.signal?.snrDb?.let { it < MIN_SEVERE_SNR_DB } == true && snapshot.hasBitErrors
        severeRfSamples = if (severeRfFailure) severeRfSamples + 1 else 0

        if (snapshot.hasFreshOscamFailure) {
            if (oscamFailureSinceEpochMillis == null) {
                oscamFailureSinceEpochMillis = snapshot.sampledAtEpochMillis
            }
        } else {
            oscamFailureSinceEpochMillis = null
        }

        val oscamFailureLongEnough = oscamFailureSinceEpochMillis?.let { firstFailure ->
            snapshot.sampledAtEpochMillis - firstFailure >= OSCAM_CONTINUOUS_FAILURE_MILLIS
        } == true

        return when {
            severeRfSamples >= SEVERE_RF_CONSECUTIVE_SAMPLES -> LiveTvSatFailureReason.BIT_ERRORS
            oscamFailureLongEnough -> LiveTvSatFailureReason.OSCAM
            else -> null
        }
    }

    fun reset() {
        severeRfSamples = 0
        oscamFailureSinceEpochMillis = null
    }

    companion object {
        /** Intentionally below the normal rain-fade warning range; SAT remains preferred. */
        internal const val MIN_SEVERE_SNR_DB = 6.0
        internal const val OSCAM_CONTINUOUS_FAILURE_MILLIS = 5_000L
        internal const val SEVERE_RF_CONSECUTIVE_SAMPLES = 5
    }
}

/**
 * Historical SAT health helper retained for compatibility/tests. The player no longer bypasses SAT
 * because SAT is always the preferred source on a fresh channel selection.
 */
internal object LiveTvSatCircuitBreaker {
    private val lock = Any()
    private val failureTimes = ArrayDeque<Long>()
    private var degradedUntilMillis = 0L

    fun recordFailure(nowMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        prune(nowMillis)
        failureTimes.addLast(nowMillis)
        if (failureTimes.size >= FAILURE_COUNT_FOR_DEGRADED) {
            degradedUntilMillis = maxOf(degradedUntilMillis, nowMillis + DEGRADED_DURATION_MILLIS)
            failureTimes.clear()
        }
    }

    fun isDegraded(nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (degradedUntilMillis <= nowMillis) {
            degradedUntilMillis = 0L
            prune(nowMillis)
            false
        } else {
            true
        }
    }

    internal fun resetForTest() = synchronized(lock) {
        failureTimes.clear()
        degradedUntilMillis = 0L
    }

    private fun prune(nowMillis: Long) {
        while (failureTimes.firstOrNull()?.let { nowMillis - it > FAILURE_WINDOW_MILLIS } == true) {
            failureTimes.removeFirst()
        }
    }

    private const val FAILURE_COUNT_FOR_DEGRADED = 3
    private const val FAILURE_WINDOW_MILLIS = 90_000L
    private const val DEGRADED_DURATION_MILLIS = 3 * 60_000L
}
