package com.andreassamitsch.ilauncher.ui.livetv

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifState
import java.text.DateFormat
import java.util.Date

@Composable
fun LiveTvScreen(
    state: OpenWebifState,
    onSaveConnection: (baseUrl: String, username: String, password: String) -> Unit,
    onSelectBouquet: (serviceReference: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Live TV / Gigablue", style = MaterialTheme.typography.displaySmall)
        Text(
            "Direkte lokale Verbindung über Enigma2/OpenWebif. In Phase 5 werden Bouquets, Sender und EPG Now/Next eingebunden; interne Wiedergabe folgt in Phase 7.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Receiver", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (state.configured) {
                "Konfiguriert: ${state.receiverLabel ?: "lokaler Receiver"}${if (state.username.isNotBlank()) " · Benutzer gesetzt" else ""}${if (state.hasPassword) " · Passwort gesetzt" else ""}"
            } else {
                "Noch keine Gigablue konfiguriert."
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Button(
            onClick = {
                showConnectionDialog(
                    context = context,
                    initialBaseUrl = state.baseUrl,
                    initialUsername = state.username,
                    hasStoredPassword = state.hasPassword,
                    onSave = onSaveConnection,
                )
            },
        ) {
            Text(if (state.configured) "Receiver bearbeiten" else "Receiver einrichten")
        }

        if (state.configured) {
            Button(onClick = onRefresh, enabled = !state.isRefreshing) {
                Text(if (state.isRefreshing) "Aktualisiere …" else "Verbindung testen / aktualisieren")
            }
        }

        state.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.lastUpdatedUtcMillis?.let { updated ->
            Text(
                text = "Letzte erfolgreiche Aktualisierung: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.bouquets.isNotEmpty()) {
            Text("Bouquet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Dieses Bouquet speist die Reihe „Jetzt im TV“. Die Reihenfolge der Sender wird von der Gigablue übernommen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.bouquets.forEach { bouquet ->
                val selected = bouquet.serviceReference == state.selectedBouquetRef
                Button(
                    onClick = { onSelectBouquet(bouquet.serviceReference) },
                    enabled = !state.isRefreshing,
                ) {
                    Text(if (selected) "✓ ${bouquet.name}" else bouquet.name)
                }
            }
        }

        if (state.channels.isNotEmpty()) {
            Text("Sender / EPG Now & Next", style = MaterialTheme.typography.headlineSmall)
            state.channels.take(30).forEachIndexed { index, channel ->
                Text(
                    text = buildString {
                        append(index + 1)
                        append(". ")
                        append(channel.name)
                        channel.now?.let { append(" · jetzt: ${it.title}") }
                        channel.next?.let { append(" · danach: ${it.title}") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.channels.size > 30) {
                Text(
                    "+ ${state.channels.size - 30} weitere Sender",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.configured && state.channels.isEmpty() && !state.isRefreshing && state.errorMessage == null) {
            Text(
                "Noch keine Sender geladen. Starte „Verbindung testen / aktualisieren“.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Sicherheit: Zugangsdaten werden nur lokal in I Launcher gespeichert und nie in Diagnose/Logs ausgegeben. HTTP-Basic-Authentifizierung sollte nur im vertrauenswürdigen Heimnetz verwendet werden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun showConnectionDialog(
    context: Context,
    initialBaseUrl: String,
    initialUsername: String,
    hasStoredPassword: Boolean,
    onSave: (String, String, String) -> Unit,
) {
    val padding = (20 * context.resources.displayMetrics.density).toInt()
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding / 2, padding, 0)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    val address = EditText(context).apply {
        hint = "Receiver-Adresse, z. B. 192.168.1.20"
        setText(initialBaseUrl)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
    }
    val username = EditText(context).apply {
        hint = "Benutzername (optional)"
        setText(initialUsername)
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
    }
    val password = EditText(context).apply {
        hint = if (hasStoredPassword) {
            "Passwort neu eingeben (leer = entfernen)"
        } else {
            "Passwort (optional)"
        }
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
    }
    container.addView(address)
    container.addView(username)
    container.addView(password)

    AlertDialog.Builder(context)
        .setTitle("Gigablue / OpenWebif")
        .setView(container)
        .setMessage("Ohne Schema wird http:// verwendet. Port kann angegeben werden, z. B. 192.168.1.20:80.")
        .setPositiveButton("Speichern") { _, _ ->
            onSave(address.text.toString(), username.text.toString(), password.text.toString())
        }
        .setNegativeButton("Abbrechen", null)
        .show()
}
