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

class PinSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = JoynParentalPinSettings(applicationContext)
        setContent {
            JoynTvTheme {
                PinSettingsScreen(settings = settings, onBack = { finish() })
            }
        }
    }
}

@Composable
private fun PinSettingsScreen(
    settings: JoynParentalPinSettings,
    onBack: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var hasStoredPin by remember { mutableStateOf(settings.hasPin()) }
    var autoUse by remember { mutableStateOf(settings.autoUse()) }
    var message by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080A0E))) {
        val compact = maxHeight < 520.dp
        val horizontal = if (compact) 28.dp else 64.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontal, vertical = if (compact) 26.dp else 48.dp),
        ) {
            Text(
                "Jugendschutz-PIN",
                color = Color.White,
                fontSize = if (compact) 30.sp else 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Hier wird der bereits in deinem Joyn-Konto festgelegte 4-stellige PIN lokal hinterlegt. Die App ändert den PIN bei Joyn nicht. Bei geschützten Inhalten wird er im Entitlement-Request an Joyn gesendet.",
                color = Color(0xFFD7DBE3),
                fontSize = if (compact) 13.sp else 15.sp,
                lineHeight = if (compact) 18.sp else 21.sp,
                modifier = Modifier.widthIn(max = 900.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                if (hasStoredPin) "Gespeicherter PIN: ••••" else "Noch kein PIN gespeichert",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(18.dp))

            Text("Neuen / geänderten PIN eingeben", color = Color(0xFFD7DBE3), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(4); message = null },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 19.sp, letterSpacing = 5.sp),
                cursorBrush = SolidColor(Color.White),
                visualTransformation = PasswordVisualTransformation(),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 360.dp)
                            .background(Color(0xFF171C24), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF4F5966), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        if (pin.isBlank()) Text("4 Ziffern", color = Color(0xFF929AA6), fontSize = 16.sp)
                        inner()
                    }
                },
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))

            Text("Automatisch verwenden", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PinChoice("Ja", autoUse) { autoUse = true }
                PinChoice("Nein", !autoUse) { autoUse = false }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Bei „Ja“ wird der gespeicherte PIN automatisch versucht. Lehnt Joyn ihn ab, erscheint trotzdem die PIN-Abfrage im Player.",
                color = Color(0xFF9FA8B5),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PinAction("Zurück", onClick = onBack)
                PinAction("Speichern", enabled = pin.length == 4) {
                    runCatching {
                        settings.save(pin, autoUse)
                        hasStoredPin = true
                        pin = ""
                        message = "PIN wurde verschlüsselt auf diesem Gerät gespeichert."
                    }.onFailure { message = it.message ?: it.javaClass.simpleName }
                }
                if (hasStoredPin) {
                    PinAction("Gespeicherten PIN löschen") {
                        settings.clear()
                        hasStoredPin = false
                        autoUse = false
                        pin = ""
                        message = "Gespeicherter PIN wurde entfernt."
                    }
                }
            }

            message?.let {
                Spacer(Modifier.height(18.dp))
                Text(it, color = Color(0xFFD7DBE3), fontSize = 13.sp)
            }
            Spacer(Modifier.height(44.dp))
        }
    }
}

@Composable
private fun PinChoice(label: String, selected: Boolean, onClick: () -> Unit) {
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
        Text(label, color = if (selected || focused) Color(0xFF11151B) else Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun PinAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
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
