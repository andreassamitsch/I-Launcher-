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
    BIT_ERRORS("SAT-Bitfehler"),
    OSCAM("OSCam-Entschlüsselung fehlgeschlagen"),
    BUFFERING("SAT-Stream puffert zu lange"),
    PLAYBACK("SAT-Wiedergabe fehlgeschlagen"),
    RECEIVER("Gigablue nicht erreichbar"),
}

/**
 * Conservative debouncing so one transient tuner/ECM sample never flips the source.
 *
 * OSCam gets an additional startup grace period. Some encrypted channels legitimately need a few
 * seconds until the first successful ECM arrives, so a fresh timeout/not-found result must not move
 * playback to Joyn before that grace period has elapsed.
 */
internal class LiveTvSatHealthPolicy(
    private val sessionStartedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    private var lowSnrSamples = 0
    private var berSamples = 0
    private var oscamFailureSamples = 0

    fun update(snapshot: LiveTvReceptionSnapshot): LiveTvSatFailureReason? {
        lowSnrSamples = if (snapshot.signal?.snrDb?.let { it < MIN_STABLE_SNR_DB } == true) {
            lowSnrSamples + 1
        } else {
            0
        }
        berSamples = if (snapshot.hasBitErrors) berSamples + 1 else 0
        oscamFailureSamples = if (snapshot.hasFreshOscamFailure) oscamFailureSamples + 1 else 0

        val oscamGraceExpired =
            snapshot.sampledAtEpochMillis - sessionStartedAtEpochMillis >= OSCAM_STARTUP_GRACE_MILLIS

        return when {
            lowSnrSamples >= LOW_SNR_CONSECUTIVE_SAMPLES -> LiveTvSatFailureReason.LOW_SNR
            berSamples >= BER_CONSECUTIVE_SAMPLES -> LiveTvSatFailureReason.BIT_ERRORS
            oscamGraceExpired && oscamFailureSamples >= OSCAM_CONSECUTIVE_SAMPLES -> LiveTvSatFailureReason.OSCAM
            else -> null
        }
    }

    fun reset() {
        lowSnrSamples = 0
        berSamples = 0
        oscamFailureSamples = 0
    }

    companion object {
        /** Below this the tested DVB-S2 path is already in the practical rain-fade danger zone. */
        internal const val MIN_STABLE_SNR_DB = 6.5
        internal const val OSCAM_STARTUP_GRACE_MILLIS = 5_000L
        private const val LOW_SNR_CONSECUTIVE_SAMPLES = 3
        private const val BER_CONSECUTIVE_SAMPLES = 2
        private const val OSCAM_CONSECUTIVE_SAMPLES = 3
    }
}

/**
 * Short circuit breaker for a receiver/weather outage. After several SAT fallbacks while zapping,
 * channels that have a Joyn mapping start directly on Joyn for a few minutes. It never affects
 * bouquet channels without Joyn coverage.
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
