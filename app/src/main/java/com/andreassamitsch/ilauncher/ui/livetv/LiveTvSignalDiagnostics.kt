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
import com.andreassamitsch.ilauncher.data.oscam.OscamClientStatus
import com.andreassamitsch.ilauncher.data.oscam.OscamReadResult
import com.andreassamitsch.ilauncher.data.oscam.OscamStatusReader
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import java.util.Locale
import kotlinx.coroutines.delay

private const val SIGNAL_POLL_INTERVAL_MILLIS = 1_000L
private const val SIGNAL_REALIGN_RECHECK_MILLIS = 250L
private const val SIGNAL_REALIGN_COOLDOWN_MILLIS = 2_500L
private const val SIGNAL_EMPTY_GRACE_POLLS = 3
private const val OSCAM_POLL_INTERVAL_MILLIS = 1_000L

/**
 * Live diagnostics for the current SAT path and OSCam DVBAPI decryption.
 *
 * RF reception and decryption intentionally remain separate lines. Good SNR/BER proves only that
 * the transponder is received cleanly; OSCam status tells us whether the current SID gets a fresh
 * ECM/control-word response and which reader answered it. This separation is the basis for a later
 * SAT -> Joyn fallback decision without mistaking a CAM/reader problem for bad satellite reception.
 */
@Composable
internal fun LiveTvSignalDiagnostics(channel: LiveTvChannel) {
    val context = LocalContext.current
    val reader = remember(context) { OpenWebifSignalReader(context) }
    val oscamReader = remember(context) { OscamStatusReader(context) }
    var status by remember(channel.serviceReference) { mutableStateOf<OpenWebifSignalStatus?>(null) }
    var unavailable by remember(channel.serviceReference) { mutableStateOf(false) }
    var oscamResult by remember(channel.serviceReference) { mutableStateOf<OscamReadResult?>(null) }

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

    LaunchedEffect(oscamReader, channel.serviceReference) {
        while (true) {
            oscamResult = oscamReader.read(channel.serviceReference)
            delay(OSCAM_POLL_INTERVAL_MILLIS)
        }
    }

    val signalLine = status?.takeIf { it.hasMeasurements }?.let(::formatSignalDiagnostics)
        ?: if (unavailable) "SAT-Signal · keine Tunerwerte" else "SAT-Signal · wird gelesen …"

    Text(
        signalLine,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        oscamResult?.let(::formatOscamDiagnostics) ?: "OSCam · wird gelesen …",
        style = MaterialTheme.typography.bodySmall,
        color = when (val result = oscamResult) {
            is OscamReadResult.Match -> if (result.status.failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            is OscamReadResult.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
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

internal fun formatOscamDiagnostics(result: OscamReadResult): String = when (result) {
    is OscamReadResult.NoMatchingEcm ->
        "OSCam · keine passende ECM · SID ${result.serviceId.asSidHex()} (FTA oder noch keine Anfrage)"

    is OscamReadResult.Error -> "OSCam ✕ · ${result.message}"

    is OscamReadResult.Match -> formatOscamMatch(result.status)
}

private fun formatOscamMatch(status: OscamClientStatus): String {
    if (!status.fresh) {
        return buildList {
            add("OSCam · ECM veraltet")
            add("SID ${status.serviceId.asSidHex()}")
            status.idleSeconds?.let { add("idle ${it}s") }
        }.joinToString(" · ")
    }

    return buildList {
        when {
            status.failed -> add("OSCam ✕")
            !status.answered.isNullOrBlank() -> add("OSCam ✓")
            else -> add("OSCam · ECM wartet")
        }
        status.caid?.let { add("CAID $it") }
        status.providerId?.let { add("PROVID $it") }
        if (status.failed) {
            status.answered?.let { add(it) }
        } else {
            status.answered?.let { add("Reader $it") }
        }
        status.ecmTimeMs?.let { add("ECM $it ms") }
        status.idleSeconds?.takeIf { it > 2 }?.let { add("idle ${it}s") }
    }.joinToString(" · ")
}

private fun Int.asSidHex(): String = "%04X".format(Locale.US, this)
