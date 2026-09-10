package com.andreassamitsch.joyntv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

class ProxySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        setContent {
            JoynTvTheme {
                ProxySettingsScreen(
                    initial = repository.proxyConfig(),
                    country = repository.currentCountry(),
                    savedNordUsername = repository.nordVpnServiceUsername(),
                    savedNordPassword = repository.nordVpnServicePassword(),
                    onAutoResolve = { allTraffic, onProgress ->
                        repository.findAutomaticProxy(allTraffic, onProgress)
                    },
                    onNordResolve = { username, password, allTraffic, onProgress ->
                        repository.findNordVpnProxy(username, password, allTraffic, onProgress)
                    },
                    onRememberNordCredentials = { username, password ->
                        repository.saveNordVpnServiceCredentials(username, password)
                    },
                    onSave = { config ->
                        repository.setProxy(config)
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        finish()
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun ProxySettingsScreen(
    initial: JoynProxyConfig,
    country: JoynCountry,
    savedNordUsername: String,
    savedNordPassword: String,
    onAutoResolve: suspend (
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit,
    ) -> JoynProxyDiscoveryResult,
    onNordResolve: suspend (
        username: String,
        password: String,
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit,
    ) -> JoynProxyDiscoveryResult,
    onRememberNordCredentials: (String, String) -> Unit,
    onSave: (JoynProxyConfig) -> Unit,
    onBack: () -> Unit,
) {
    val initialNord = initial.automatic && initial.source.startsWith("NordVPN")
    var enabled by remember { mutableStateOf(initial.enabled) }
    var automatic by remember { mutableStateOf(initial.automatic) }
    var nordVpn by remember { mutableStateOf(initialNord) }
    var allTraffic by remember { mutableStateOf(initial.allTraffic) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.takeIf { it > 0 }?.toString().orEmpty()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var nordUsername by remember {
        mutableStateOf(savedNordUsername.ifBlank { if (initialNord) initial.username else "" })
    }
    var nordPassword by remember {
        mutableStateOf(savedNordPassword.ifBlank { if (initialNord) initial.password else "" })
    }
    var testing by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (initial.enabled && initial.automatic && initial.isUsable) {
                buildString {
                    append("Aktuell automatisch gewählt: ${initial.host}:${initial.port}")
                    if (initial.latencyMs >= 0) append(" · ${initial.latencyMs} ms")
                    if (initial.source.isNotBlank()) append(" · ${initial.source}")
                }
            } else {
                ""
            },
        )
    }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080A0E))) {
        val compact = maxHeight < 520.dp
        val horizontal = if (compact) 28.dp else 64.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontal, vertical = if (compact) 24.dp else 46.dp),
        ) {
            Text(
                "Joyn Test-Proxy",
                color = Color.White,
                fontSize = if (compact) 29.sp else 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Für DE/AT/CH-Tests kann Joyns Steuerverkehr über einen Proxy im Zielland laufen. Ein automatisch gewählter Proxy wird erst aktiviert, wenn auch Joyn GraphQL und eine echte Live-Freigabe funktionieren.",
                color = Color(0xFFD7DBE3),
                fontSize = if (compact) 13.sp else 15.sp,
                lineHeight = if (compact) 18.sp else 21.sp,
                modifier = Modifier.widthIn(max = 900.dp),
            )
            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProxyChoice("Proxy aus", !enabled) {
                    if (!testing) enabled = false
                }
                ProxyChoice("Proxy an", enabled) {
                    if (!testing) enabled = true
                }
            }

            if (enabled) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProxyChoice("Automatisch", automatic) {
                        if (!testing) automatic = true
                    }
                    ProxyChoice("Manuell", !automatic) {
                        if (!testing) automatic = false
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProxyChoice("Nur API / Token", !allTraffic) {
                        if (!testing) allTraffic = false
                    }
                    ProxyChoice("Alles inkl. Stream", allTraffic) {
                        if (!testing) allTraffic = true
                    }
                }
                Spacer(Modifier.height(22.dp))

                if (automatic) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProxyChoice("Öffentliche Proxys", !nordVpn) {
                            if (!testing) nordVpn = false
                        }
                        ProxyChoice("NordVPN", nordVpn) {
                            if (!testing) nordVpn = true
                        }
                    }
                    Spacer(Modifier.height(18.dp))

                    if (nordVpn) {
                        Text(
                            "NordVPN für ${country.name}: Die App lädt aktuelle NordVPN-Server des Ziellandes und testet jeden Server zuerst als HTTPS/CONNECT-Proxy auf Port 89 und anschließend als SOCKS5 auf Port 1080. Aktiviert wird nur ein Server, mit dem Joyn Live tatsächlich freigegeben wird.",
                            color = Color(0xFFD7DBE3),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.widthIn(max = 900.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Verwende die NordVPN-Service-Zugangsdaten aus der manuellen Einrichtung, nicht zwingend E-Mail und Account-Passwort. Die Daten werden auf diesem Gerät gespeichert, damit du sie nur einmal eingeben musst.",
                            color = Color(0xFFF1C27D),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.widthIn(max = 900.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        ProxyFieldLabel("NordVPN Service-Benutzername")
                        ProxyField(nordUsername, { nordUsername = it }, "Service username", false)
                        Spacer(Modifier.height(12.dp))
                        ProxyFieldLabel("NordVPN Service-Passwort")
                        ProxyField(nordPassword, { nordPassword = it }, "Service password", true)
                    } else {
                        Text(
                            "Öffentliche Proxys für ${country.name}: Die App lädt aktuelle Proxylisten und verwirft Kandidaten, die Joyns API- oder Live-Prüfung nicht bestehen.",
                            color = Color(0xFFD7DBE3),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.widthIn(max = 900.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Öffentliche Gratis-Proxys werden von Joyn häufig als VPN/Proxy erkannt. Diese Option bleibt vor allem für Tests erhalten.",
                            color = Color(0xFFF1C27D),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.widthIn(max = 900.dp),
                        )
                    }

                    if (status.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            status,
                            color = if (testing) Color(0xFFD7DBE3) else Color(0xFF9FD6AE),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.widthIn(max = 900.dp),
                        )
                    }
                } else {
                    ProxyFieldLabel("Proxy Host")
                    ProxyField(host, { host = it }, "z. B. de1465.nordvpn.com", false)
                    Spacer(Modifier.height(12.dp))
                    ProxyFieldLabel("Port")
                    ProxyField(port, { port = it.filter(Char::isDigit).take(5) }, "89", false)
                    Spacer(Modifier.height(12.dp))
                    ProxyFieldLabel("Benutzername (optional)")
                    ProxyField(username, { username = it }, "proxy-user", false)
                    Spacer(Modifier.height(12.dp))
                    ProxyFieldLabel("Passwort (optional)")
                    ProxyField(password, { password = it }, "proxy-passwort", true)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                when {
                    !enabled -> "Proxy ist deaktiviert. Joyn verwendet die normale Internetverbindung."
                    automatic && nordVpn -> "NordVPN-Automatik: Serverliste laden → Port 89 testen → SOCKS5/1080 testen → Joyn Live prüfen → ersten geeigneten Server aktivieren."
                    automatic -> "Öffentliche Automatik: Nur Proxys, die Joyns vollständige Live-Prüfung bestehen, werden aktiviert."
                    else -> "Aktiv: ${if (allTraffic) "gesamter App-Verkehr" else "Joyn API/Auth/Entitlement/Playlist"} über den manuellen Proxy. Beim Speichern wird die aktuelle Joyn-Sitzung verworfen und neu aufgebaut."
                },
                color = Color(0xFF9FA8B5),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.widthIn(max = 900.dp),
            )
            Spacer(Modifier.height(22.dp))

            val manualValid = host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535
            val nordValid = nordUsername.isNotBlank() && nordPassword.isNotBlank()
            val saveEnabled = !testing && (!enabled || (automatic && (!nordVpn || nordValid)) || (!automatic && manualValid))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProxyAction("Zurück", enabled = !testing, onClick = onBack)
                ProxyAction(
                    label = when {
                        testing -> "Proxys werden getestet …"
                        enabled && automatic && nordVpn -> "NordVPN suchen & aktivieren"
                        enabled && automatic -> "Suchen & aktivieren"
                        else -> "Speichern"
                    },
                    enabled = saveEnabled,
                ) {
                    when {
                        !enabled -> onSave(
                            initial.copy(
                                enabled = false,
                                automatic = automatic,
                                allTraffic = allTraffic,
                            ),
                        )

                        !automatic -> onSave(
                            JoynProxyConfig(
                                enabled = true,
                                automatic = false,
                                host = host.trim(),
                                port = port.toIntOrNull() ?: 0,
                                username = username,
                                password = password,
                                allTraffic = allTraffic,
                            ),
                        )

                        else -> scope.launch {
                            testing = true
                            val result = if (nordVpn) {
                                onRememberNordCredentials(nordUsername, nordPassword)
                                status = "Lade NordVPN-Server für ${country.name} …"
                                runCatching {
                                    onNordResolve(nordUsername, nordPassword, allTraffic) { progress ->
                                        status = progress.message
                                    }
                                }.getOrElse { error ->
                                    JoynProxyDiscoveryResult(
                                        config = null,
                                        candidates = 0,
                                        attempted = 0,
                                        message = "NordVPN-Suche fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}",
                                    )
                                }
                            } else {
                                status = "Lade aktuelle Proxylisten für ${country.name} …"
                                runCatching {
                                    onAutoResolve(allTraffic) { progress ->
                                        status = progress.message
                                    }
                                }.getOrElse { error ->
                                    JoynProxyDiscoveryResult(
                                        config = null,
                                        candidates = 0,
                                        attempted = 0,
                                        message = "Proxy-Suche fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}",
                                    )
                                }
                            }
                            testing = false
                            val config = result.config
                            if (config != null) {
                                status = result.message
                                onSave(config)
                            } else {
                                status = result.message
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(44.dp))
        }
    }
}

@Composable
private fun ProxyFieldLabel(text: String) {
    Text(text, color = Color(0xFFD7DBE3), fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ProxyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171C24), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF4F5966), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                if (value.isBlank()) Text(placeholder, color = Color(0xFF929AA6), fontSize = 15.sp)
                inner()
            }
        },
        modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
    )
}

@Composable
private fun ProxyChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected || focused) Color.White else Color(0xFF171C24))
            .border(1.dp, if (focused) Color.White else Color(0xFF535D6A), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (selected || focused) Color(0xFF11151B) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ProxyAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    !enabled -> Color(0xFF252A32)
                    focused -> Color.White
                    else -> Color(0xFF1B212A)
                },
            )
            .border(1.dp, if (focused) Color.White else Color(0xFF535D6A), shape)
            .onFocusChanged { focused = it.isFocused }
            .then(if (enabled) Modifier.clickable(onClick = onClick).focusable() else Modifier)
            .padding(horizontal = 22.dp, vertical = 11.dp),
    ) {
        Text(
            label,
            color = if (focused && enabled) Color(0xFF11151B) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
