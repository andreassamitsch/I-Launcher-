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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

class ProxySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        val country = repository.currentCountry()
        setContent {
            JoynTvTheme {
                NetworkSettingsScreen(
                    country = country,
                    initial = repository.proxyConfig(),
                    countrySettings = repository.mysteriumCountrySettings(country),
                    savedWireGuard = repository.mysteriumWireGuardProfile(country),
                    loggedIn = repository.mysteriumHasStoredAccessToken(),
                    onOpenAccount = { startActivity(Intent(this, MysteriumSettingsActivity::class.java)) },
                    onStop = repository::stopMysteriumResidentialScan,
                    onUseDirect = {
                        repository.setProxy(repository.proxyConfig().copy(enabled = false, automatic = false, source = ""))
                        finish()
                    },
                    onTest = { attempts, allTraffic, onProgress ->
                        repository.findMysteriumResidentialProxy(country, attempts, allTraffic, onProgress)
                    },
                    onActivate = { config ->
                        repository.setProxy(config)
                        finish()
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun NetworkSettingsScreen(
    country: JoynCountry,
    initial: JoynProxyConfig,
    countrySettings: JoynMysteriumCountrySettings,
    savedWireGuard: JoynMysteriumWireGuardProfile?,
    loggedIn: Boolean,
    onOpenAccount: () -> Unit,
    onStop: () -> Unit,
    onUseDirect: () -> Unit,
    onTest: suspend (Int, Boolean, (JoynProxyDiscoveryProgress) -> Unit) -> JoynProxyDiscoveryResult,
    onActivate: (JoynProxyConfig) -> Unit,
    onBack: () -> Unit,
) {
    var attempts by rememberSaveable { mutableStateOf(countrySettings.maxAttempts.toString()) }
    var allTraffic by rememberSaveable {
        mutableStateOf(if (initial.isMysterium) initial.allTraffic else countrySettings.allTraffic)
    }
    var testing by remember { mutableStateOf(false) }
    var status by rememberSaveable {
        mutableStateOf(
            when {
                savedWireGuard?.enabled == true ->
                    "Dauerhaft aktiv für ${country.name} · Mysterium Residential · Exit ${savedWireGuard.exitIp}\n" +
                        "Beim Start und beim Länderwechsel wird genau dieses bereits von Joyn akzeptierte Profil automatisch wiederhergestellt."
                savedWireGuard != null ->
                    "Residential-Profil für ${country.name} gespeichert (${savedWireGuard.exitIp}), derzeit aber deaktiviert."
                initial.isUsable && initial.isMysterium ->
                    "Aktiv: Mysterium Residential · ${initial.host}:${initial.port}"
                else -> "Direkte Verbindung aktiv. Für ${country.name} ist noch kein dauerhaftes Residential-Profil gespeichert."
            },
        )
    }
    val scope = rememberCoroutineScope()
    val count = attempts.toIntOrNull() ?: 0
    val validCount = count in JoynMysteriumSettings.MIN_ATTEMPTS..JoynMysteriumSettings.MAX_ATTEMPTS

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF080A0E))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = 38.dp),
    ) {
        Text("Joyn Netzwerk", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Mysterium Residential wird je Joyn-Land separat gespeichert. Ein erfolgreich getesteter Exit bleibt für dieses Land hinterlegt und wird beim Länderwechsel automatisch wieder aktiviert.",
            color = Color(0xFFD7DBE3), fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.widthIn(max = 900.dp),
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NetAction("Für ${country.name} direkt verwenden", enabled = !testing, onClick = onUseDirect)
            NetAction("Mysterium Konto & Login", enabled = !testing, onClick = onOpenAccount)
            NetAction("Zurück", enabled = !testing, onClick = onBack)
        }
        Spacer(Modifier.height(24.dp))
        Text("Residential-Profil ${country.name}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            if (loggedIn) "Mysterium-Session gespeichert." else "Noch nicht bei Mysterium angemeldet.",
            color = if (loggedIn) Color(0xFF9FD6AE) else Color(0xFFF1C27D), fontSize = 13.sp,
        )
        savedWireGuard?.let { saved ->
            Spacer(Modifier.height(8.dp))
            Text(
                if (saved.enabled) {
                    "Gespeicherter Joyn-Treffer: ${saved.exitIp} · automatische Wiederherstellung EIN"
                } else {
                    "Gespeicherter Joyn-Treffer: ${saved.exitIp} · automatische Wiederherstellung AUS"
                },
                color = if (saved.enabled) Color(0xFF9FD6AE) else Color(0xFFF1C27D),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("Max. neue Residential-IPs testen (1–100)", color = Color(0xFFD7DBE3), fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = attempts,
            onValueChange = { attempts = it.filter(Char::isDigit).take(3) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            decorationBox = { inner ->
                Box(
                    Modifier.background(Color(0xFF171C24), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF535D6A), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .widthIn(min = 150.dp, max = 260.dp),
                ) { inner() }
            },
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NetChoice("Nur API / Token", !allTraffic) { if (!testing) allTraffic = false }
            NetChoice("Alles inkl. Stream", allTraffic) { if (!testing) allTraffic = true }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NetAction("Residential testen & dauerhaft aktivieren", enabled = !testing && loggedIn && validCount) {
                scope.launch {
                    testing = true
                    status = "Starte Mysterium Residential ${country.name} …"
                    val result = runCatching {
                        onTest(count, allTraffic) { status = it.message }
                    }.getOrElse { error ->
                        JoynProxyDiscoveryResult(
                            null,
                            count,
                            0,
                            "Mysterium-Test fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                    testing = false
                    status = if (result.activated) {
                        result.message + "\n\nDieses Profil ist jetzt dauerhaft ${country.name} zugeordnet und wird automatisch wieder aktiviert."
                    } else {
                        result.message
                    }
                    result.config?.let(onActivate)
                }
            }
            if (testing) {
                NetAction("Test stoppen") {
                    status = "Stop angefordert …"
                    onStop()
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            status,
            color = Color(0xFFD7DBE3),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.widthIn(max = 1000.dp),
        )
        Spacer(Modifier.height(42.dp))
    }
}

@Composable
private fun NetChoice(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun NetAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
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
