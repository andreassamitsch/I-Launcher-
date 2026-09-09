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

class ProxySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        setContent {
            JoynTvTheme {
                ProxySettingsScreen(
                    initial = repository.proxyConfig(),
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
    onSave: (JoynProxyConfig) -> Unit,
    onBack: () -> Unit,
) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var allTraffic by remember { mutableStateOf(initial.allTraffic) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.takeIf { it > 0 }?.toString().orEmpty()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }

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
                "Für DE/CH-Tests kann nur Joyns Steuerverkehr über einen Proxy im Zielland laufen. Standardmäßig bleiben Manifest, Widevine und Videosegmente auf deiner direkten Verbindung. Nur wenn Joyn beim eigentlichen Stream nochmals die IP prüft, aktiviere testweise den Vollproxy.",
                color = Color(0xFFD7DBE3),
                fontSize = if (compact) 13.sp else 15.sp,
                lineHeight = if (compact) 18.sp else 21.sp,
                modifier = Modifier.widthIn(max = 900.dp),
            )
            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProxyChoice("Proxy aus", !enabled) { enabled = false }
                ProxyChoice("Proxy an", enabled) { enabled = true }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProxyChoice("Nur API / Token", !allTraffic) { allTraffic = false }
                ProxyChoice("Alles inkl. Stream", allTraffic) { allTraffic = true }
            }
            Spacer(Modifier.height(22.dp))

            ProxyFieldLabel("Proxy Host")
            ProxyField(host, { host = it }, "z. B. de-proxy.meinserver.net", false)
            Spacer(Modifier.height(12.dp))
            ProxyFieldLabel("Port")
            ProxyField(port, { port = it.filter(Char::isDigit).take(5) }, "8080", false)
            Spacer(Modifier.height(12.dp))
            ProxyFieldLabel("Benutzername (optional)")
            ProxyField(username, { username = it }, "proxy-user", false)
            Spacer(Modifier.height(12.dp))
            ProxyFieldLabel("Passwort (optional)")
            ProxyField(password, { password = it }, "proxy-passwort", true)

            Spacer(Modifier.height(24.dp))
            Text(
                if (enabled) {
                    "Aktiv: ${if (allTraffic) "gesamter App-Verkehr" else "Joyn API/Auth/Entitlement/Playlist"} über Proxy. Beim Speichern wird die aktuelle Joyn-Sitzung verworfen und neu aufgebaut."
                } else {
                    "Proxy ist deaktiviert. Joyn verwendet die normale Internetverbindung."
                },
                color = Color(0xFF9FA8B5),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.widthIn(max = 900.dp),
            )
            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProxyAction("Zurück", onBack)
                ProxyAction(
                    "Speichern",
                    enabled = !enabled || (host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535),
                ) {
                    onSave(
                        JoynProxyConfig(
                            enabled = enabled,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 0,
                            username = username,
                            password = password,
                            allTraffic = allTraffic,
                        ),
                    )
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
