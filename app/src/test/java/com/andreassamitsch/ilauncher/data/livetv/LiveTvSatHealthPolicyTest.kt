package com.andreassamitsch.ilauncher.data.livetv

import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifSignalStatus
import com.andreassamitsch.ilauncher.data.oscam.OscamClientStatus
import com.andreassamitsch.ilauncher.data.oscam.OscamReadResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvSatHealthPolicyTest {
    @After
    fun cleanup() {
        LiveTvSatCircuitBreaker.resetForTest()
    }

    @Test
    fun `low SNR requires three consecutive samples`() {
        val policy = LiveTvSatHealthPolicy()
        val weak = snapshot(snrDb = 6.1)

        assertNull(policy.update(weak))
        assertNull(policy.update(weak))
        assertEquals(LiveTvSatFailureReason.LOW_SNR, policy.update(weak))
    }

    @Test
    fun `healthy SNR resets low signal debounce`() {
        val policy = LiveTvSatHealthPolicy()

        assertNull(policy.update(snapshot(snrDb = 6.0)))
        assertNull(policy.update(snapshot(snrDb = 6.0)))
        assertNull(policy.update(snapshot(snrDb = 9.8)))
        assertNull(policy.update(snapshot(snrDb = 6.0)))
        assertNull(policy.update(snapshot(snrDb = 6.0)))
        assertEquals(LiveTvSatFailureReason.LOW_SNR, policy.update(snapshot(snrDb = 6.0)))
    }

    @Test
    fun `BER greater than zero needs two consecutive samples`() {
        val policy = LiveTvSatHealthPolicy()

        assertNull(policy.update(snapshot(ber = "12")))
        assertEquals(LiveTvSatFailureReason.BIT_ERRORS, policy.update(snapshot(ber = "4")))
    }

    @Test
    fun `fresh OSCam failure needs three samples`() {
        val policy = LiveTvSatHealthPolicy()
        val failure = snapshot(
            oscam = OscamReadResult.Match(
                OscamClientStatus(
                    serviceId = 0x132F,
                    answered = "timeout",
                    idleSeconds = 0,
                ),
            ),
        )

        assertNull(policy.update(failure))
        assertNull(policy.update(failure))
        assertEquals(LiveTvSatFailureReason.OSCAM, policy.update(failure))
    }

    @Test
    fun `no matching ECM is not treated as decryption failure`() {
        val policy = LiveTvSatHealthPolicy()
        val ftaOrWaiting = snapshot(oscam = OscamReadResult.NoMatchingEcm(0x132F))

        repeat(5) { assertNull(policy.update(ftaOrWaiting)) }
    }

    @Test
    fun `circuit breaker opens after three SAT failures and expires`() {
        val start = 1_000_000L

        LiveTvSatCircuitBreaker.recordFailure(start)
        LiveTvSatCircuitBreaker.recordFailure(start + 10_000L)
        assertFalse(LiveTvSatCircuitBreaker.isDegraded(start + 20_000L))
        LiveTvSatCircuitBreaker.recordFailure(start + 20_000L)
        assertTrue(LiveTvSatCircuitBreaker.isDegraded(start + 21_000L))
        assertFalse(LiveTvSatCircuitBreaker.isDegraded(start + 3 * 60_000L + 21_000L))
    }

    private fun snapshot(
        snrDb: Double = 9.8,
        ber: String = "0",
        oscam: OscamReadResult? = OscamReadResult.NoMatchingEcm(0x132F),
    ) = LiveTvReceptionSnapshot(
        signal = OpenWebifSignalStatus(
            snrPercent = 61,
            snrDb = snrDb,
            agcPercent = 41,
            ber = ber,
        ),
        signalUnavailable = false,
        oscam = oscam,
        sampledAtEpochMillis = 0L,
    )
}
