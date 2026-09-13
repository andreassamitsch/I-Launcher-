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
import kotlinx.coroutines.delay

private const val SIGNAL_POLL_INTERVAL_MILLIS = 1_000L

/**
 * Small diagnostic line for the currently active Enigma2 tuner.
 *
 * This intentionally reports raw reception measurements only. SNR/AGC/BER can tell us whether the
 * satellite RF path is degrading, but they cannot prove that a scrambled service is successfully
 * decrypted. Decryption health will be a separate input for the later SAT -> Joyn fallback policy.
 */
@Composable
internal fun LiveTvSignalDiagnostics() {
    val context = LocalContext.current
    val reader = remember(context) { OpenWebifSignalReader(context) }
    var status by remember { mutableStateOf<OpenWebifSignalStatus?>(null) }
    var unavailable by remember { mutableStateOf(false) }

    LaunchedEffect(reader) {
        while (true) {
            reader.read()
                .onSuccess {
                    status = it
                    unavailable = !it.hasMeasurements
                }
                .onFailure {
                    unavailable = status == null
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
