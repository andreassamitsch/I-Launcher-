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
    val hybridTester = remember(context) { JoynPuls4HybridTester(context.applicationContext) }
    val profileTester = remember(context) { JoynPuls4ProfileTester(context.applicationContext) }
    val bauerTester = remember(context) { JoynBauerSuchtFrauProfileTester(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var hybridRunning by remember { mutableStateOf(false) }
    var hybridReport by remember { mutableStateOf<JoynPuls4HybridReport?>(null) }
    var hybridError by remember { mutableStateOf<String?>(null) }

    var profileRunning by remember { mutableStateOf(false) }
    var profileReport by remember { mutableStateOf<JoynPuls4ProfileReport?>(null) }
    var profileError by remember { mutableStateOf<String?>(null) }

    var bauerRunning by remember { mutableStateOf(false) }
    var bauerReport by remember { mutableStateOf<JoynBauerSuchtFrauProfileReport?>(null) }
    var bauerError by remember { mutableStateOf<String?>(null) }

    val anyRunning = hybridRunning || profileRunning || bauerRunning
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

        Spacer(Modifier.height(12.dp))
        TestSectionHeading("PULS 4 Hybrid-Qualitätstest", compact)
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
            label = if (hybridRunning) "Hybrid-Test läuft …" else "PULS 4 Hybrid-Test starten",
            enabled = !anyRunning,
        ) {
            hybridRunning = true
            hybridReport = null
            hybridError = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { hybridTester.run() }
                }.onSuccess {
                    hybridReport = it
                }.onFailure {
                    hybridError = (it.message ?: it.javaClass.simpleName).replace('\n', ' ').take(700)
                }
                hybridRunning = false
            }
        }

        when {
            hybridRunning -> {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Sender-ID, Entitlement, Playlist und DASH-Qualitäten werden geprüft …",
                    color = Color(0xFFD7DBE3),
                    fontSize = if (compact) 13.sp else 15.sp,
                )
            }

            hybridError != null -> {
                Spacer(Modifier.height(18.dp))
                TestMessageCard(
                    title = "Hybrid-Test konnte nicht gestartet werden",
                    body = hybridError.orEmpty(),
                    error = true,
                    compact = compact,
                )
            }

            hybridReport != null -> {
                val current = hybridReport
                if (current != null) {
                    Spacer(Modifier.height(18.dp))
                    TestReportHeader(
                        channelTitle = current.channelTitle,
                        channelId = current.channelId,
                        detail = buildString {
                            append(current.chLeaseSource)
                            current.chLeaseExpiresAt?.let { append(" · gültig bis $it") }
                        },
                        compact = compact,
                    )
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
        }

        Spacer(Modifier.height(if (compact) 26.dp else 36.dp))
        TestSectionHeading("PULS 4 Account- & Playerprofil-Test", compact)
        Spacer(Modifier.height(6.dp))
        Text(
            "Prüft PULS 4 ausschließlich über die direkte österreichische Verbindung. Verglichen werden anonymer und gespeicherter AT-Account sowie Browser- und Android-TV-Playlistprofile. " +
                "Zusätzlich wird getestet, ob eine reine Erhöhung von maxResolution auf 2160 die angebotene DASH-Leiter verändert.",
            color = Color(0xFFBFC6D0),
            fontSize = if (compact) 12.sp else 14.sp,
            lineHeight = if (compact) 17.sp else 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Der Test verändert keine gespeicherte Joyn-Anmeldung. Ein abgelaufener Account-Token wird für den Test nur temporär im Arbeitsspeicher erneuert.",
            color = Color(0xFF9FA8B5),
            fontSize = if (compact) 11.sp else 13.sp,
        )
        Spacer(Modifier.height(if (compact) 14.dp else 18.dp))

        TestActionChip(
            label = if (profileRunning) "Profil-Test läuft …" else "PULS 4 Account-/Profil-Test starten",
            enabled = !anyRunning,
        ) {
            profileRunning = true
            profileReport = null
            profileError = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { profileTester.run() }
                }.onSuccess {
                    profileReport = it
                }.onFailure {
                    profileError = (it.message ?: it.javaClass.simpleName).replace('\n', ' ').take(700)
                }
                profileRunning = false
            }
        }

        when {
            profileRunning -> {
                Spacer(Modifier.height(18.dp))
                Text(
                    "AT-Account, Player-Payloads und DASH-Repräsentationen werden verglichen …",
                    color = Color(0xFFD7DBE3),
                    fontSize = if (compact) 13.sp else 15.sp,
                )
            }

            profileError != null -> {
                Spacer(Modifier.height(18.dp))
                TestMessageCard(
                    title = "Account-/Profil-Test konnte nicht gestartet werden",
                    body = profileError.orEmpty(),
                    error = true,
                    compact = compact,
                )
            }

            profileReport != null -> {
                val current = profileReport
                if (current != null) {
                    Spacer(Modifier.height(18.dp))
                    TestReportHeader(
                        channelTitle = current.channelTitle,
                        channelId = current.channelId,
                        detail = "Direkter AT-Pfad · kein Proxy/VPN",
                        compact = compact,
                    )
                    TestMessageCard(
                        title = "AT-Account",
                        body = current.accountStatus,
                        error = false,
                        compact = compact,
                    )
                    Spacer(Modifier.height(8.dp))
                    current.results.forEach { result ->
                        TestResultCard(result, compact)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(if (compact) 26.dp else 36.dp))
        TestSectionHeading("Bauer sucht Frau · VOD Account- & Playerprofil-Test", compact)
        Spacer(Modifier.height(6.dp))
        Text(
            "Sucht bei jedem Testlauf automatisch die aktuellste auf Joyn AT verfügbare und abspielbare Folge von „Bauer sucht Frau“ und führt darauf denselben Profilvergleich wie beim PULS-4-Livetest aus. " +
                "Damit sehen wir, ob die 576p-Grenze nur den Live-Feed betrifft oder auch das VOD-Angebot.",
            color = Color(0xFFBFC6D0),
            fontSize = if (compact) 12.sp else 14.sp,
            lineHeight = if (compact) 17.sp else 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Auswahl: höchste verfügbare Staffel → höchste Folge mit Video-ID. Direkter AT-Pfad, kein Proxy/VPN; der Test lädt nur Katalog, Entitlement, Playlist und DASH-Manifest.",
            color = Color(0xFF9FA8B5),
            fontSize = if (compact) 11.sp else 13.sp,
        )
        Spacer(Modifier.height(if (compact) 14.dp else 18.dp))

        TestActionChip(
            label = if (bauerRunning) "VOD-Profil-Test läuft …" else "Bauer sucht Frau VOD-Test starten",
            enabled = !anyRunning,
        ) {
            bauerRunning = true
            bauerReport = null
            bauerError = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { bauerTester.run() }
                }.onSuccess {
                    bauerReport = it
                }.onFailure {
                    bauerError = (it.message ?: it.javaClass.simpleName).replace('\n', ' ').take(900)
                }
                bauerRunning = false
            }
        }

        when {
            bauerRunning -> {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Aktuellste Folge wird gesucht; danach werden VOD-Entitlement, Player-Payloads und DASH-Repräsentationen geprüft …",
                    color = Color(0xFFD7DBE3),
                    fontSize = if (compact) 13.sp else 15.sp,
                )
            }

            bauerError != null -> {
                Spacer(Modifier.height(18.dp))
                TestMessageCard(
                    title = "Bauer-sucht-Frau-VOD-Test konnte nicht gestartet werden",
                    body = bauerError.orEmpty(),
                    error = true,
                    compact = compact,
                )
            }

            bauerReport != null -> {
                val current = bauerReport
                if (current != null) {
                    Spacer(Modifier.height(18.dp))
                    val seasonEpisode = buildString {
                        current.seasonNumber?.let { append("Staffel $it") }
                        if (isNotEmpty() && current.episodeNumber != null) append(" · ")
                        current.episodeNumber?.let { append("Folge $it") }
                        if (isEmpty()) append("Staffel/Folge nicht nummeriert")
                    }
                    TestReportHeader(
                        channelTitle = "${current.seriesTitle} · $seasonEpisode · ${current.episodeTitle}",
                        channelId = current.videoId,
                        detail = "Direkter AT-VOD-Pfad · ${current.seriesPath}",
                        compact = compact,
                    )
                    TestMessageCard(
                        title = "AT-Account",
                        body = current.accountStatus,
                        error = false,
                        compact = compact,
                    )
                    Spacer(Modifier.height(8.dp))
                    current.results.forEach { result ->
                        TestResultCard(result, compact)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(if (compact) 28.dp else 40.dp))
    }
}

@Composable
private fun TestSectionHeading(title: String, compact: Boolean) {
    Text(
        title,
        color = Color(0xFFE2E5EA),
        fontSize = if (compact) 17.sp else 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun TestReportHeader(
    channelTitle: String,
    channelId: String,
    detail: String,
    compact: Boolean,
) {
    Text(
        "$channelTitle · $channelId",
        color = Color.White,
        fontSize = if (compact) 14.sp else 16.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        detail,
        color = Color(0xFF9FA8B5),
        fontSize = if (compact) 10.sp else 12.sp,
    )
    Spacer(Modifier.height(10.dp))
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
