package com.andreassamitsch.joyntv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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

class MysteriumSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        setContent {
            JoynTvTheme {
                MysteriumSettingsScreen(repository = repository, onBack = { finish() })
            }
        }
    }
}

@Composable
private fun MysteriumSettingsScreen(repository: JoynRepository, onBack: () -> Unit) {
    var countryName by rememberSaveable { mutableStateOf(repository.currentCountry().name) }
    val country = JoynCountry.valueOf(countryName)
    var email by rememberSaveable { mutableStateOf(repository.mysteriumSavedEmail()) }
    var codeOrLink by rememberSaveable { mutableStateOf("") }
    var manualToken by rememberSaveable { mutableStateOf("") }
    var attempts by rememberSaveable { mutableStateOf(repository.mysteriumCountrySettings(country).maxAttempts.toString()) }
    var authenticated by remember { mutableStateOf(repository.mysteriumHasStoredAccessToken()) }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var status by rememberSaveable {
        mutableStateOf(if (authenticated) "Mysterium-Session gespeichert." else "Noch nicht angemeldet.")
    }
    val scope = rememberCoroutineScope()

    fun loadCountry(next: JoynCountry) {
        countryName = next.name
        attempts = repository.mysteriumCountrySettings(next).maxAttempts.toString()
    }

    fun checkAccount() {
        if (busy || testing) return
        scope.launch {
            busy = true
            status = "Prüfe Mysterium-Konto …"
            val result = runCatching { repository.mysteriumApiStatus() }.getOrElse { error ->
                JoynMysteriumApiStatus(
                    false,
                    null,
                    message = "Prüfung fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}",
                )
            }
            authenticated = result.authenticated == true
            status = result.message
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (authenticated) checkAccount()
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF080A0E))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = 38.dp),
    ) {
        Text("Mysterium Residential Proxy", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Joyn TV nutzt Mysteriums kurzlebige Residential-HTTP-Proxies direkt. Es wird kein Android-VPN aufgebaut: API, Anmeldung, Manifest, DRM und Stream-Segmente laufen über einen lokalen CONNECT-Bridge zum gewählten Residential-Exit. Ein vorhandenes WireGuard-Profil bleibt nur als Fallback erhalten.",
            color = Color(0xFFD7DBE3),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.widthIn(max = 950.dp),
        )
        Spacer(Modifier.height(22.dp))

        Text("1 · Konto", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        FieldLabel("E-Mail")
        MField(email, { email = it }, "name@example.com", false)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MAction("Magic-Link senden", enabled = !busy && !testing && email.contains('@')) {
                scope.launch {
                    busy = true
                    status = "Fordere Magic-Link an …"
                    repository.requestMysteriumMagicLink(email).onSuccess { login ->
                        authenticated = login.authenticated
                        status = login.message
                    }.onFailure { error ->
                        status = "Magic-Link fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
                    }
                    busy = false
                }
            }
            MAction("Konto prüfen", enabled = !busy && !testing, onClick = ::checkAccount)
            if (repository.mysteriumHasStoredAccessToken()) {
                MAction("Abmelden", enabled = !busy && !testing) {
                    scope.launch {
                        repository.disconnectMysteriumVpn()
                        repository.logoutMysterium()
                        repository.disableProxy()
                        authenticated = false
                        status = "Mysterium-Session gelöscht; Proxy/VPN getrennt."
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FieldLabel("Code oder Link aus der Mysterium-Mail")
        MField(codeOrLink, { codeOrLink = it }, "…?code=…", false)
        Spacer(Modifier.height(8.dp))
        MAction("Mit Magic-Link anmelden", enabled = !busy && !testing && codeOrLink.isNotBlank()) {
            scope.launch {
                busy = true
                status = "Schließe Anmeldung ab …"
                repository.completeMysteriumMagicLink(codeOrLink).onSuccess {
                    authenticated = true
                    codeOrLink = ""
                    status = "Mysterium-Anmeldung erfolgreich."
                }.onFailure { error ->
                    status = "Anmeldung fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
                }
                busy = false
            }
        }

        Spacer(Modifier.height(12.dp))
        FieldLabel("Access-Token (optional)")
        MField(manualToken, { manualToken = it }, "Bearer-Token", true)
        Spacer(Modifier.height(8.dp))
        MAction("Access-Token speichern", enabled = !busy && !testing && manualToken.isNotBlank()) {
            repository.saveMysteriumAccessToken(manualToken)
            manualToken = ""
            authenticated = true
            status = "Access-Token gespeichert."
        }

        Spacer(Modifier.height(24.dp))
        Text("2 · Residential-Proxy", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            JoynCountry.entries.forEach { item ->
                MChoice(item.name, item == country) { if (!busy && !testing) loadCountry(item) }
            }
        }
        Spacer(Modifier.height(12.dp))
        FieldLabel("Max. neue Residential-IPs testen (1–100)")
        MField(attempts, { attempts = it.filter(Char::isDigit).take(3) }, "25", false)
        Spacer(Modifier.height(10.dp))
        Text(
            "Für Leckschutz wird der gesamte Datenverkehr dieser Joyn-TV-App über den aktiven Mysterium-Proxy geführt. Andere Apps und das Android-TV-System bleiben auf der normalen Internetverbindung. Eine VPN-Berechtigung ist dafür nicht nötig.",
            color = Color(0xFFADB5C1),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.widthIn(max = 950.dp),
        )

        Spacer(Modifier.height(18.dp))
        Text(
            status,
            color = Color(0xFFD7DBE3),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.widthIn(max = 1000.dp),
        )
        Spacer(Modifier.height(20.dp))
        val count = attempts.toIntOrNull() ?: 0
        val valid = count in JoynMysteriumSettings.MIN_ATTEMPTS..JoynMysteriumSettings.MAX_ATTEMPTS
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MAction("Zurück", enabled = !busy && !testing, onClick = onBack)
            MAction("Profil speichern", enabled = !busy && !testing && valid) {
                repository.saveMysteriumSettings(country, count, allTraffic = true)
                status = "${country.name}-Proxyprofil gespeichert."
            }
            MAction("Residential-Proxy testen & aktivieren", enabled = !busy && !testing && valid && authenticated) {
                scope.launch {
                    testing = true
                    status = "Starte Mysterium Residential ${country.name} über HTTP-Proxy …"
                    val result = runCatching {
                        repository.findMysteriumResidentialProxy(
                            country = country,
                            maxAttempts = count,
                            allTraffic = true,
                        ) { status = it.message }
                    }.getOrElse { error ->
                        JoynProxyDiscoveryResult(
                            config = null,
                            candidates = count,
                            attempted = 0,
                            message = "Test fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                    testing = false
                    status = result.message
                    result.config?.let { config ->
                        repository.setCountry(country)
                        repository.setProxy(config.copy(allTraffic = true))
                        status = result.message + "\n\nHTTP-Proxy für Joyn TV aktiviert · Markt ${country.name}."
                    }
                }
            }
            if (testing) {
                MAction("Test stoppen") {
                    status = "Stop angefordert …"
                    repository.stopMysteriumResidentialScan()
                }
            }
            MAction("Proxy/VPN trennen", enabled = !busy && !testing) {
                scope.launch {
                    repository.disableProxy()
                    repository.disconnectMysteriumVpn().onSuccess {
                        status = "Mysterium-Proxy und WireGuard-Fallback getrennt."
                    }.onFailure { error ->
                        status = "Proxy getrennt; VPN-Fallback konnte nicht getrennt werden: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }
        }
        Spacer(Modifier.height(42.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Color(0xFFD7DBE3), fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun MField(value: String, onValueChange: (String) -> Unit, placeholder: String, password: Boolean) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        decorationBox = { inner ->
            Box(
                Modifier.background(Color(0xFF171C24), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF535D6A), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(min = 300.dp, max = 720.dp),
            ) {
                if (value.isBlank()) Text(placeholder, color = Color(0xFF929AA6), fontSize = 14.sp)
                inner()
            }
        },
    )
}

@Composable
private fun MChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected || focused) Color.White else Color(0xFF171C24))
            .border(1.dp, if (focused) Color.White else Color(0xFF535D6A), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 17.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (selected || focused) Color(0xFF11151B) else Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun MAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.clip(shape)
            .background(if (focused && enabled) Color.White else Color(0xFF171C24))
            .border(1.dp, if (focused && enabled) Color.White else Color(0xFF535D6A), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .focusable(enabled)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(
            label,
            color = when {
                !enabled -> Color(0xFF68717D)
                focused -> Color(0xFF11151B)
                else -> Color.White
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
