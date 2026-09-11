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
                MysteriumSettingsScreen(
                    repository = repository,
                    onBack = { finish() },
                    onOpenJoyn = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun MysteriumSettingsScreen(
    repository: JoynRepository,
    onBack: () -> Unit,
    onOpenJoyn: () -> Unit,
) {
    var selectedCountryName by rememberSaveable { mutableStateOf(repository.currentCountry().name) }
    val selectedCountry = JoynCountry.valueOf(selectedCountryName)
    var apiBaseUrl by rememberSaveable { mutableStateOf(repository.mysteriumApiBaseUrl()) }
    var email by rememberSaveable { mutableStateOf(repository.mysteriumSavedEmail()) }
    var magicCodeOrLink by rememberSaveable { mutableStateOf("") }
    var manualToken by rememberSaveable { mutableStateOf("") }
    var attempts by rememberSaveable {
        mutableStateOf(repository.mysteriumCountrySettings(selectedCountry).maxAttempts.toString())
    }
    var allTraffic by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var accountBusy by remember { mutableStateOf(false) }
    var status by rememberSaveable {
        mutableStateOf(
            if (repository.mysteriumHasStoredAccessToken()) {
                "Mysterium-Session gespeichert. Konto wird geprüft …"
            } else {
                "Noch nicht bei Mysterium angemeldet."
            },
        )
    }
    var accountAuthenticated by remember { mutableStateOf(repository.mysteriumHasStoredAccessToken()) }
    val scope = rememberCoroutineScope()

    fun loadCountry(country: JoynCountry) {
        selectedCountryName = country.name
        attempts = repository.mysteriumCountrySettings(country).maxAttempts.toString()
    }

    fun checkAccount() {
        if (accountBusy || testing) return
        scope.launch {
            accountBusy = true
            status = "Prüfe Mysterium API und Konto …"
            val result = runCatching { repository.mysteriumApiStatus(apiBaseUrl) }
                .getOrElse { error ->
                    JoynMysteriumApiStatus(
                        reachable = false,
                        authenticated = null,
                        message = "Mysterium-Prüfung fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            if (result.resolvedBaseUrl.isNotBlank()) apiBaseUrl = result.resolvedBaseUrl
            accountAuthenticated = result.authenticated == true
            status = result.message
            accountBusy = false
        }
    }

    LaunchedEffect(Unit) {
        if (repository.mysteriumHasStoredAccessToken()) checkAccount()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080A0E))) {
        val compact = maxHeight < 520.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (compact) 28.dp else 64.dp,
                    vertical = if (compact) 22.dp else 42.dp,
                ),
        ) {
            Text(
                "Mysterium Residential",
                color = Color.White,
                fontSize = if (compact) 29.sp else 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Private Residential-IP-Adressen für Joyn testen. Pro Land werden die Einstellungen separat gespeichert; Joyn TV wechselt automatisch auf neue Residential-IPs und stoppt beim ersten Exit, der die echte Live-Freigabe besteht.",
                color = Color(0xFFD7DBE3),
                fontSize = if (compact) 13.sp else 15.sp,
                lineHeight = if (compact) 18.sp else 21.sp,
                modifier = Modifier.widthIn(max = 980.dp),
            )

            Spacer(Modifier.height(22.dp))
            SectionTitle("1 · Mysterium-Konto")
            Text(
                "Mysterium verwendet passwordlosen Login. Magic-Link anfordern und danach die Link-Adresse bzw. den code=…-Wert hier einfügen. Alternativ kann für Tests ein vorhandener Access-Token gespeichert werden.",
                color = Color(0xFFF1C27D),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.widthIn(max = 980.dp),
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("E-Mail")
            MysteriumField(email, { email = it }, "name@example.com", false)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MysteriumAction("Magic-Link senden", enabled = !accountBusy && !testing && email.contains('@')) {
                    scope.launch {
                        accountBusy = true
                        repository.saveMysteriumSettings(
                            apiBaseUrl,
                            selectedCountry,
                            attempts.toIntOrNull() ?: JoynMysteriumCountrySettings.DEFAULT_ATTEMPTS,
                        )
                        status = "Fordere Mysterium Magic-Link an …"
                        val result = repository.requestMysteriumMagicLink(email, apiBaseUrl)
                        result.onSuccess { login ->
                            accountAuthenticated = login.authenticated
                            status = login.message
                            if (login.authenticated) checkAccount()
                        }.onFailure { error ->
                            status = "Magic-Link fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
                        }
                        accountBusy = false
                    }
                }
                MysteriumAction("Konto prüfen", enabled = !accountBusy && !testing) { checkAccount() }
                if (repository.mysteriumHasStoredAccessToken()) {
                    MysteriumAction("Abmelden", enabled = !accountBusy && !testing) {
                        repository.logoutMysterium()
                        accountAuthenticated = false
                        status = "Mysterium-Session gelöscht."
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            FieldLabel("Code oder komplette Link-Adresse aus der Mysterium-Mail")
            MysteriumField(
                magicCodeOrLink,
                { magicCodeOrLink = it },
                "mysteriumvpn://…?code=… oder UUID-Code",
                false,
            )
            Spacer(Modifier.height(8.dp))
            MysteriumAction(
                "Mit Magic-Link anmelden",
                enabled = !accountBusy && !testing && magicCodeOrLink.isNotBlank(),
            ) {
                scope.launch {
                    accountBusy = true
                    status = "Schließe Mysterium-Anmeldung ab …"
                    val result = repository.completeMysteriumMagicLink(magicCodeOrLink, apiBaseUrl)
                    result.onSuccess {
                        accountAuthenticated = true
                        magicCodeOrLink = ""
                        status = "Mysterium-Anmeldung erfolgreich."
                        checkAccount()
                    }.onFailure { error ->
                        status = "Mysterium-Anmeldung fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
                    }
                    accountBusy = false
                }
            }

            Spacer(Modifier.height(14.dp))
            FieldLabel("Access-Token (optional, nur falls bereits vorhanden)")
            MysteriumField(manualToken, { manualToken = it }, "Bearer-Token", true)
            Spacer(Modifier.height(8.dp))
            MysteriumAction("Access-Token speichern", enabled = !accountBusy && !testing && manualToken.isNotBlank()) {
                repository.saveMysteriumAccessToken(manualToken)
                manualToken = ""
                accountAuthenticated = true
                status = "Access-Token gespeichert."
                checkAccount()
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("2 · Länderprofil")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JoynCountry.entries.forEach { country ->
                    MysteriumChoice(country.name, selectedCountry == country) {
                        if (!testing && !accountBusy) loadCountry(country)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${selectedCountry.name}: Residential-only ist fest aktiviert. Ein erfolgreicher Test speichert den Proxy für dieses Land und setzt Joyn auf dasselbe Land.",
                color = Color(0xFFD7DBE3),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("Max. neue Residential-IPs testen (1–100)")
            MysteriumField(
                attempts,
                { attempts = it.filter(Char::isDigit).take(3) },
                "25",
                false,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MysteriumChoice("Nur Joyn API / Token", !allTraffic) { if (!testing) allTraffic = false }
                MysteriumChoice("Alles inkl. Stream", allTraffic) { if (!testing) allTraffic = true }
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("Mysterium API (Erweitert)")
            MysteriumField(
                apiBaseUrl,
                { apiBaseUrl = it },
                JoynMysteriumSettings.DEFAULT_API_BASE_URL,
                false,
            )
            Text(
                "Standard ist die Mysterium Consumer API. Falls Mysterium die Backend-Adresse ändert, kann sie hier ohne App-Update überschrieben werden.",
                color = Color(0xFF9FA8B5),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.widthIn(max = 980.dp),
            )

            if (status.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    status,
                    color = if (testing || accountBusy) Color(0xFFD7DBE3) else Color(0xFF9FD6AE),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.widthIn(max = 1040.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            val attemptCount = attempts.toIntOrNull() ?: 0
            val profileValid = attemptCount in JoynMysteriumSettings.MIN_ATTEMPTS..JoynMysteriumSettings.MAX_ATTEMPTS
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MysteriumAction("Zurück", enabled = !testing && !accountBusy, onClick = onBack)
                MysteriumAction("Profil speichern", enabled = !testing && !accountBusy && profileValid) {
                    repository.saveMysteriumSettings(apiBaseUrl, selectedCountry, attemptCount)
                    status = "${selectedCountry.name}-Profil gespeichert · Residential-only · max. $attemptCount IPs."
                }
                MysteriumAction(
                    if (testing) "Residential-IPs werden getestet …" else "Residential-IPs testen & aktivieren",
                    enabled = !testing && !accountBusy && profileValid && accountAuthenticated,
                ) {
                    scope.launch {
                        testing = true
                        repository.saveMysteriumSettings(apiBaseUrl, selectedCountry, attemptCount)
                        status = "Starte Mysterium Residential ${selectedCountry.name} …"
                        val result = runCatching {
                            repository.findMysteriumResidentialProxy(
                                country = selectedCountry,
                                apiBaseUrl = apiBaseUrl,
                                maxAttempts = attemptCount,
                                allTraffic = allTraffic,
                            ) { progress -> status = progress.message }
                        }.getOrElse { error ->
                            JoynProxyDiscoveryResult(
                                config = null,
                                candidates = attemptCount,
                                attempted = 0,
                                message = "Mysterium-Test fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}",
                            )
                        }
                        testing = false
                        status = result.message
                        result.config?.let { config ->
                            repository.setCountry(selectedCountry)
                            repository.setProxy(config)
                            status = result.message + "\n\nAktiviert für ${selectedCountry.name}."
                        }
                    }
                }
                if (testing) {
                    MysteriumAction("Test stoppen") {
                        status = "Stop angefordert … bisherige Residential-IP-Auswertung wird vorbereitet."
                        repository.stopMysteriumResidentialScan()
                    }
                }
                if (!testing && repository.proxyConfig().source.startsWith("Mysterium")) {
                    MysteriumAction("Joyn öffnen", onClick = onOpenJoyn)
                }
            }
            Spacer(Modifier.height(44.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Color(0xFFD7DBE3), fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun MysteriumField(
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
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
    )
}

@Composable
private fun MysteriumChoice(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun MysteriumAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
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
