package com.andreassamitsch.joyntv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Experimental diagnostics. Nothing in this panel is part of normal Joyn playback/routing. */
@Composable
internal fun JoynTestsPanel(compact: Boolean) {
    val context = LocalContext.current
    val tester = remember(context) { JoynPuls4HybridTester(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<JoynPuls4HybridReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val horizontal = if (compact) 28.dp else 64.dp
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = horizontal, end = horizontal, top = if (compact) 18.dp else 28.dp),
    ) {
        Text(
            "Tests",
            color = Color.White,
            fontSize = if (compact) 26.sp else 34.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "PULS 4 Hybrid-Qualitätstest",
            color = Color(0xFFE2E5EA),
            fontSize = if (compact) 17.sp else 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Vergleicht vier Kombinationen aus österreichischer Joyn-Identität und direktem bzw. Schweizer Residential-Netz. " +
                "Der Test verwendet eigene temporäre Netzwerk-Clients, ändert keine Länder-, Proxy- oder Player-Einstellung und lädt nur bis zum DASH-Manifest.",
            color = Color(0xFFBFC6D0),
            fontSize = if (compact) 12.sp else 14.sp,
            lineHeight = if (compact) 17.sp else 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Voraussetzung: ein noch gültiger, zuvor erfolgreich getesteter Mysterium-Residential-Lease für die Schweiz.",
            color = Color(0xFF9FA8B5),
            fontSize = if (compact) 11.sp else 13.sp,
        )
        Spacer(Modifier.height(if (compact) 14.dp else 18.dp))

        TestActionChip(
            label = if (running) "Test läuft …" else "PULS 4 Hybrid-Test starten",
            enabled = !running,
        ) {
            running = true
            report = null
            error = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { tester.run() }
                }.onSuccess {
                    report = it
                }.onFailure {
                    error = (it.message ?: it.javaClass.simpleName).replace('\n', ' ').take(700)
                }
                running = false
            }
        }

        when {
            running -> {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Sender-ID, Entitlement, Playlist und DASH-Qualitäten werden geprüft …",
                    color = Color(0xFFD7DBE3),
                    fontSize = if (compact) 13.sp else 15.sp,
                )
            }

            error != null -> {
                Spacer(Modifier.height(18.dp))
                TestMessageCard(
                    title = "Test konnte nicht gestartet werden",
                    body = error.orEmpty(),
                    error = true,
                    compact = compact,
                )
            }

            report != null -> {
                val current = report ?: return@Column
                Spacer(Modifier.height(18.dp))
                Text(
                    "${current.channelTitle} · ${current.channelId}",
                    color = Color.White,
                    fontSize = if (compact) 14.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(current.chLeaseSource)
                        current.chLeaseExpiresAt?.let { append(" · gültig bis $it") }
                    },
                    color = Color(0xFF9FA8B5),
                    fontSize = if (compact) 10.sp else 12.sp,
                )
                Spacer(Modifier.height(10.dp))

                current.results.forEach { result ->
                    TestResultCard(result, compact)
                    Spacer(Modifier.height(8.dp))
                }

                if (current.bridgeFailures.isNotEmpty()) {
                    TestMessageCard(
                        title = "CH-Proxy-Hinweise",
                        body = current.bridgeFailures.joinToString(" · "),
                        error = true,
                        compact = compact,
                    )
                }
            }
        }
        Spacer(Modifier.height(if (compact) 28.dp else 40.dp))
    }
}

@Composable
private fun TestActionChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val modifier = Modifier
        .clip(shape)
        .background(
            when {
                !enabled -> Color(0xFF242A33)
                focused -> Color.White
                else -> Color(0xFF171C24)
            },
        )
        .border(
            1.dp,
            when {
                focused && enabled -> Color.White
                enabled -> Color(0xFF596573)
                else -> Color(0xFF353C46)
            },
            shape,
        )
        .then(
            if (enabled) {
                Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(onClick = onClick)
                    .focusable()
            } else {
                Modifier
            },
        )
        .padding(horizontal = 16.dp, vertical = 9.dp)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            color = if (focused && enabled) Color(0xFF11151B) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TestResultCard(result: JoynPuls4HybridCaseResult, compact: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xD8171C24))
            .border(1.dp, if (result.success) Color(0xFF44515F) else Color(0xFF784C50), shape)
            .padding(if (compact) 12.dp else 15.dp),
    ) {
        Text(
            result.label,
            color = Color.White,
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        if (result.success) {
            Text(
                qualityText(result.quality),
                color = Color(0xFFDCE3EC),
                fontSize = if (compact) 12.sp else 14.sp,
            )
            result.manifestHost?.takeIf(String::isNotBlank)?.let { host ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "Manifest: $host",
                    color = Color(0xFF9FA8B5),
                    fontSize = if (compact) 10.sp else 11.sp,
                )
            }
        } else {
            Text(
                result.error ?: "Unbekannter Fehler",
                color = Color(0xFFFFC5C5),
                fontSize = if (compact) 11.sp else 13.sp,
                lineHeight = if (compact) 15.sp else 18.sp,
            )
        }
    }
}

@Composable
private fun TestMessageCard(title: String, body: String, error: Boolean, compact: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xD8171C24))
            .border(1.dp, if (error) Color(0xFF784C50) else Color(0xFF44515F), shape)
            .padding(if (compact) 12.dp else 15.dp),
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            color = if (error) Color(0xFFFFC5C5) else Color(0xFFDCE3EC),
            fontSize = if (compact) 11.sp else 13.sp,
            lineHeight = if (compact) 15.sp else 18.sp,
        )
    }
}

private fun qualityText(quality: DashQuality?): String {
    quality ?: return "DASH-Manifest geladen, Qualitätsdaten nicht ermittelbar"
    val resolution = when {
        quality.width != null && quality.height != null -> "${quality.width}×${quality.height}"
        quality.height != null -> "${quality.height}p"
        else -> "Auflösung unbekannt"
    }
    val bitrate = quality.maxBitrate?.let {
        String.format(Locale.US, "%.1f Mbit/s", it / 1_000_000.0)
    }
    val fps = quality.maxFrameRate?.let {
        String.format(Locale.US, if (it % 1.0 == 0.0) "%.0f fps" else "%.2f fps", it)
    }
    return buildList {
        add(resolution)
        bitrate?.let(::add)
        fps?.let(::add)
        add("${quality.representationCount} Video-Repr.")
    }.joinToString(" · ")
}
