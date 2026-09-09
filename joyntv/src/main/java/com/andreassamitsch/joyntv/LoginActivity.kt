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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        setContent {
            JoynTvTheme {
                LoginScreen(
                    repository = repository,
                    onRegionChanged = {
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
private fun LoginScreen(
    repository: JoynRepository,
    onRegionChanged: () -> Unit,
) {
    var account by remember { mutableStateOf<JoynAccountState?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeCountry by remember { mutableStateOf(repository.currentCountry()) }
    var automaticRegion by remember { mutableStateOf(repository.countryIsAutomatic()) }
    val scope = rememberCoroutineScope()

    suspend fun refreshAccount() {
        account = withContext(Dispatchers.IO) { repository.accountState(refreshRemote = true) }
    }

    LaunchedEffect(Unit) {
        runCatching { refreshAccount() }
            .onFailure { message = it.message ?: it.javaClass.simpleName }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080A0E))) {
        val compact = maxHeight < 520.dp
        val horizontalPadding = if (compact) 28.dp else 64.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = if (compact) 28.dp else 54.dp),
        ) {
            Text(
                "Joyn Konto & Region",
                color = Color.White,
                fontSize = if (compact) 30.sp else 42.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Die Joyn-Region steuert Sender, Mediatheken und Katalog. Android TV meldet bei deutscher Sprache oft DE, auch wenn das Gerät in Österreich steht. Deshalb kann die Region hier fest eingestellt werden.",
                color = Color(0xFFD7DBE3),
                fontSize = if (compact) 13.sp else 15.sp,
                lineHeight = if (compact) 18.sp else 21.sp,
                modifier = Modifier.widthIn(max = 860.dp),
            )
            Spacer(Modifier.height(if (compact) 18.dp else 24.dp))

            Text(
                "Region: ${countryLabel(activeCountry)}${if (automaticRegion) " · automatisch" else " · fest eingestellt"}",
                color = Color.White,
                fontSize = if (compact) 16.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RegionButton("Automatisch", automaticRegion) {
                    repository.setCountry(null)
                    activeCountry = repository.currentCountry()
                    automaticRegion = true
                    onRegionChanged()
                }
                RegionButton("Österreich", !automaticRegion && activeCountry == JoynCountry.AT) {
                    repository.setCountry(JoynCountry.AT)
                    activeCountry = JoynCountry.AT
                    automaticRegion = false
                    onRegionChanged()
                }
                RegionButton("Deutschland", !automaticRegion && activeCountry == JoynCountry.DE) {
                    repository.setCountry(JoynCountry.DE)
                    activeCountry = JoynCountry.DE
                    automaticRegion = false
                    onRegionChanged()
                }
                RegionButton("Schweiz", !automaticRegion && activeCountry == JoynCountry.CH) {
                    repository.setCountry(JoynCountry.CH)
                    activeCountry = JoynCountry.CH
                    automaticRegion = false
                    onRegionChanged()
                }
            }
            Spacer(Modifier.height(if (compact) 24.dp else 36.dp))

            Text(
                "Die Anmeldung läuft direkt über Joyns aktuellen 7Pass/SSO-Ablauf. Das Passwort wird nicht gespeichert; gespeichert wird nur das von Joyn ausgegebene Sitzungstoken. Beim Regionswechsel wird dieses Token bewusst verworfen, weil Joyn Sitzungen marktabhängig sind.",
                color = Color(0xFF9FA8B5),
                fontSize = if (compact) 12.sp else 13.sp,
                lineHeight = if (compact) 17.sp else 19.sp,
                modifier = Modifier.widthIn(max = 860.dp),
            )
            Spacer(Modifier.height(if (compact) 22.dp else 34.dp))

            val current = account
            if (current?.loggedIn == true) {
                Text(
                    current.email ?: "Bei Joyn angemeldet",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(
                        if (current.hasPlus) "Joyn PLUS+" else null,
                        if (current.hasHd) "HD" else null,
                    ).ifEmpty { listOf("Joyn Konto aktiv") }.joinToString(" · "),
                    color = Color(0xFFD7DBE3),
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(24.dp))
                LoginActionButton("Abmelden", enabled = !working) {
                    scope.launch {
                        working = true
                        message = null
                        runCatching {
                            withContext(Dispatchers.IO) { repository.logout() }
                            refreshAccount()
                        }.onFailure { message = it.message ?: it.javaClass.simpleName }
                        working = false
                    }
                }
            } else {
                Text("E-Mail", color = Color(0xFFD7DBE3), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LoginField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "name@beispiel.at",
                    password = false,
                )
                Spacer(Modifier.height(14.dp))
                Text("Passwort", color = Color(0xFFD7DBE3), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LoginField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Joyn Passwort",
                    password = true,
                )
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoginActionButton(
                        label = if (working) "Anmeldung läuft …" else "Anmelden",
                        enabled = !working && email.contains('@') && password.length >= 6,
                    ) {
                        scope.launch {
                            working = true
                            message = null
                            runCatching {
                                val loggedIn = withContext(Dispatchers.IO) {
                                    repository.login(email.trim(), password)
                                }
                                password = ""
                                account = loggedIn
                            }.onFailure { error ->
                                message = error.message ?: error.javaClass.simpleName
                            }
                            working = false
                        }
                    }
                }
            }

            message?.let {
                Spacer(Modifier.height(20.dp))
                Text(
                    it.take(900),
                    color = Color(0xFFFFC5C5),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.widthIn(max = 820.dp),
                )
            }
            Spacer(Modifier.height(44.dp))
        }
    }
}

private fun countryLabel(country: JoynCountry): String = when (country) {
    JoynCountry.AT -> "Österreich"
    JoynCountry.DE -> "Deutschland"
    JoynCountry.CH -> "Schweiz"
}

@Composable
private fun RegionButton(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171C24), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF4F5966), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (value.isBlank()) {
                    Text(placeholder, color = Color(0xFF929AA6), fontSize = 16.sp)
                }
                inner()
            }
        },
        modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
    )
}

@Composable
private fun LoginActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
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
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = if (focused && enabled) Color(0xFF11151B) else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
