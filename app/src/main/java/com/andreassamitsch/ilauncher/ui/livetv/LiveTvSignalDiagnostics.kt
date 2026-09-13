package com.andreassamitsch.ilauncher.ui.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifSignalReader
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifSignalStatus
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import kotlinx.coroutines.delay

private const val SIGNAL_POLL_INTERVAL_MILLIS = 1_000L
private const val SIGNAL_REALIGN_RECHECK_MILLIS = 250L
private const val SIGNAL_REALIGN_COOLDOWN_MILLIS = 2_500L
private const val SIGNAL_EMPTY_GRACE_POLLS = 3

/**
 * Small diagnostic line for the currently active Enigma2 tuner.
 *
 * This intentionally reports raw reception measurements only. SNR/AGC/BER can tell us whether the
 * satellite RF path is degrading, but they cannot prove that a scrambled service is successfully
 * decrypted. Decryption health will be a separate input for the later SAT -> Joyn fallback policy.
 *
 * OpenWebif derives /api/signal from session.nav.getCurrentService(). On the target Gigablue the
 * foreground service can disappear exactly when the port-8001 stream becomes active, even though
 * the stream keeps using the SAT tuner. If that happens, re-align the foreground service with the
 * same channel and re-read the signal endpoint. A few empty polls are also tolerated so a transient
 * Enigma2 hand-over does not make the overlay flicker between valid values and "keine Tunerwerte".
 */
@Composable
internal fun LiveTvSignalDiagnostics(channel: LiveTvChannel) {
    val context = LocalContext.current
    val reader = remember(context) { OpenWebifSignalReader(context) }
    var status by remember(channel.serviceReference) { mutableStateOf<OpenWebifSignalStatus?>(null) }
    var unavailable by remember(channel.serviceReference) { mutableStateOf(false) }

    LaunchedEffect(reader, channel.serviceReference) {
        var emptyPolls = 0
        var lastRealignAtMillis = 0L

        while (true) {
            var result = reader.read()
            var sample = result.getOrNull()

            if (sample != null && !sample.hasMeasurements) {
                val now = System.currentTimeMillis()
                val realignAllowed = lastRealignAtMillis == 0L ||
                    now - lastRealignAtMillis >= SIGNAL_REALIGN_COOLDOWN_MILLIS
                if (realignAllowed) {
                    lastRealignAtMillis = now
                    val realigned = reader.alignCurrentService(
                        serviceReference = channel.serviceReference,
                        title = channel.name,
                    ).getOrDefault(false)
                    if (realigned) {
                        delay(SIGNAL_REALIGN_RECHECK_MILLIS)
                        result = reader.read()
                        sample = result.getOrNull()
                    }
                }
            }

            when {
                sample?.hasMeasurements == true -> {
                    status = sample
                    emptyPolls = 0
                    unavailable = false
                }

                else -> {
                    emptyPolls += 1
                    if (emptyPolls >= SIGNAL_EMPTY_GRACE_POLLS) {
                        status = null
                        unavailable = true
                    }
                }
            }

            delay(SIGNAL_POLL_INTERVAL_MILLIS)
        }
    }

    val line = status?.takeIf { it.hasMeasurements }?.let(::formatSignalDiagnostics)
        ?: if (unavailable) "SAT-Signal · keine Tunerwerte" else "SAT-Signal · wird gelesen …"

    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun formatSignalDiagnostics(status: OpenWebifSignalStatus): String = buildList {
    add("SAT")
    val tunerLabel = listOfNotNull(
        status.tunerNumber?.takeIf(String::isNotBlank)?.let { "Tuner $it" },
        status.tunerType?.takeIf(String::isNotBlank),
    ).joinToString(" ")
    if (tunerLabel.isNotBlank()) add(tunerLabel)
    status.snrPercent?.let { add("SNR $it%") }
    status.snrDb?.let { add("%.2f dB".format(it)) }
    status.agcPercent?.let { add("AGC $it%") }
    status.ber?.takeIf(String::isNotBlank)?.let { add("BER $it") }
}.joinToString(" · ")
