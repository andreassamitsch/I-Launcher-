package com.andreassamitsch.ilauncher.ui.livetv

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.livetv.LiveTvReceptionSnapshot
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifSignalStatus
import com.andreassamitsch.ilauncher.data.oscam.OscamClientStatus
import com.andreassamitsch.ilauncher.data.oscam.OscamReadResult
import java.util.Locale

/**
 * Renders the background reception snapshot owned by the player.
 *
 * Sampling no longer depends on whether the transient overlay is visible. This both removes
 * duplicate OpenWebif/OSCam requests and lets the same measurements drive the automatic Joyn
 * fallback after the overlay has disappeared.
 */
@Composable
internal fun LiveTvSignalDiagnostics(snapshot: LiveTvReceptionSnapshot?) {
    val signalLine = when {
        snapshot == null -> "SAT-Signal · wird gelesen …"
        snapshot.signal?.hasMeasurements == true -> formatSignalDiagnostics(snapshot.signal)
        snapshot.signalUnavailable -> "SAT-Signal · keine Tunerwerte"
        else -> "SAT-Signal · wird gelesen …"
    }

    Text(
        signalLine,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        snapshot?.oscam?.let(::formatOscamDiagnostics) ?: "OSCam · wird gelesen …",
        style = MaterialTheme.typography.bodySmall,
        color = when (val result = snapshot?.oscam) {
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
