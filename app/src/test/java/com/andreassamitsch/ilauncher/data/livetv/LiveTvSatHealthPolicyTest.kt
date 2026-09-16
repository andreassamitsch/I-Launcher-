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
    fun `weak SNR alone never flips away from SAT`() {
        val policy = LiveTvSatHealthPolicy()

        repeat(10) { index ->
            assertNull(policy.update(snapshot(snrDb = 5.5, sampledAtEpochMillis = index * 1_000L)))
        }
    }

    @Test
    fun `isolated BER alone never flips away from SAT`() {
        val policy = LiveTvSatHealthPolicy()

        repeat(10) { index ->
            assertNull(policy.update(snapshot(snrDb = 9.8, ber = "12", sampledAtEpochMillis = index * 1_000L)))
        }
    }

    @Test
    fun `very weak SNR plus BER must persist for five full seconds`() {
        val policy = LiveTvSatHealthPolicy()

        repeat(5) { index ->
            assertNull(policy.update(snapshot(snrDb = 5.5, ber = "12", sampledAtEpochMillis = index * 1_000L)))
        }
        assertEquals(
            LiveTvSatFailureReason.BIT_ERRORS,
            policy.update(snapshot(snrDb = 5.5, ber = "4", sampledAtEpochMillis = 5_000L)),
        )
    }

    @Test
    fun `healthy RF sample resets severe signal window`() {
        val policy = LiveTvSatHealthPolicy()

        repeat(5) { index ->
            assertNull(policy.update(snapshot(snrDb = 5.5, ber = "12", sampledAtEpochMillis = index * 1_000L)))
        }
        assertNull(policy.update(snapshot(snrDb = 9.8, ber = "0", sampledAtEpochMillis = 5_000L)))
        repeat(5) { index ->
            assertNull(policy.update(snapshot(snrDb = 5.5, ber = "12", sampledAtEpochMillis = 6_000L + index * 1_000L)))
        }
        assertEquals(
            LiveTvSatFailureReason.BIT_ERRORS,
            policy.update(snapshot(snrDb = 5.5, ber = "12", sampledAtEpochMillis = 11_000L)),
        )
    }

    @Test
    fun `fresh OSCam failure must remain continuous for five seconds`() {
        val policy = LiveTvSatHealthPolicy()
        val failure = oscamFailure()

        assertNull(policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 10_000L)))
        assertNull(policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 12_000L)))
        assertNull(policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 14_999L)))
        assertEquals(
            LiveTvSatFailureReason.OSCAM,
            policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 15_000L)),
        )
    }

    @Test
    fun `successful OSCam sample restarts five second failure window`() {
        val policy = LiveTvSatHealthPolicy()
        val failure = oscamFailure()
        val success = OscamReadResult.Match(
            OscamClientStatus(
                serviceId = 0x132F,
                answered = "localcard",
                idleSeconds = 0,
            ),
        )

        assertNull(policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 20_000L)))
        assertNull(policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 23_000L)))
        assertNull(policy.update(snapshot(oscam = success, sampledAtEpochMillis = 24_000L)))
        assertNull(policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 25_000L)))
        assertNull(policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 29_999L)))
        assertEquals(
            LiveTvSatFailureReason.OSCAM,
            policy.update(snapshot(oscam = failure, sampledAtEpochMillis = 30_000L)),
        )
    }

    @Test
    fun `no matching ECM is not treated as decryption failure`() {
        val policy = LiveTvSatHealthPolicy()
        val ftaOrWaiting = OscamReadResult.NoMatchingEcm(0x132F)

        repeat(10) { index ->
            assertNull(policy.update(snapshot(oscam = ftaOrWaiting, sampledAtEpochMillis = index * 1_000L)))
        }
    }

    @Test
    fun `circuit breaker helper still expires although player no longer bypasses SAT`() {
        val start = 1_000_000L

        LiveTvSatCircuitBreaker.recordFailure(start)
        LiveTvSatCircuitBreaker.recordFailure(start + 10_000L)
        assertFalse(LiveTvSatCircuitBreaker.isDegraded(start + 20_000L))
        LiveTvSatCircuitBreaker.recordFailure(start + 20_000L)
        assertTrue(LiveTvSatCircuitBreaker.isDegraded(start + 21_000L))
        assertFalse(LiveTvSatCircuitBreaker.isDegraded(start + 3 * 60_000L + 21_000L))
    }

    private fun oscamFailure() = OscamReadResult.Match(
        OscamClientStatus(
            serviceId = 0x132F,
            answered = "timeout",
            idleSeconds = 0,
        ),
    )

    private fun snapshot(
        snrDb: Double = 9.8,
        ber: String = "0",
        oscam: OscamReadResult? = OscamReadResult.NoMatchingEcm(0x132F),
        sampledAtEpochMillis: Long = 10_000L,
    ) = LiveTvReceptionSnapshot(
        signal = OpenWebifSignalStatus(
            snrPercent = 61,
            snrDb = snrDb,
            agcPercent = 41,
            ber = ber,
        ),
        signalUnavailable = false,
        oscam = oscam,
        sampledAtEpochMillis = sampledAtEpochMillis,
    )
}
