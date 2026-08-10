package com.andreassamitsch.ilauncher.ui.livetv

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.epg.EpgChannelMatcher
import com.andreassamitsch.ilauncher.data.epg.EpgSourceChannel
import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifState
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import java.text.DateFormat
import java.util.Date

@Composable
fun LiveTvScreen(
    state: OpenWebifState,
    epgState: EpgState,
    onSaveConnection: (baseUrl: String, username: String, password: String) -> Unit,
    onSelectBouquet: (serviceReference: String) -> Unit,
    onRefresh: () -> Unit,
    onSaveEpgSource: (String) -> Unit,
    onRefreshEpg: () -> Unit,
    onSetEpgMapping: (serviceReference: String, xmltvChannelId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val mappingByServiceReference = epgState.mappings.associateBy { it.serviceReference }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .touchScrollFallback(scrollState, Orientation.Vertical),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Live TV / Gigablue", style = MaterialTheme.typography.displaySmall)
        Text(
            "Gigablue/OpenWebif bleibt die Quelle für Bouquet, Senderidentität und Reihenfolge. XMLTV ergänzt den EPG; interne Wiedergabe folgt in Phase 7.",
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

        TouchButton(
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
            TouchButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                Text(if (state.isRefreshing) "Aktualisiere …" else "Gigablue aktualisieren")
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
                text = "Gigablue zuletzt: ${formatDateTime(updated)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.bouquets.isNotEmpty()) {
            Text("Bouquet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Dieses Bouquet speist „Jetzt im TV“ und den EPG. Die Senderreihenfolge wird unverändert von der Gigablue übernommen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.bouquets.forEach { bouquet ->
                val selected = bouquet.serviceReference == state.selectedBouquetRef
                TouchButton(
                    onClick = { onSelectBouquet(bouquet.serviceReference) },
                    enabled = !state.isRefreshing,
                ) {
                    Text(if (selected) "✓ ${bouquet.name}" else bouquet.name)
                }
            }
        }

        Text("EPG / XMLTV", style = MaterialTheme.typography.headlineSmall)
        Text(
            "M3U-Metadatenquelle: ${epgState.sourceLabel}. Aus der M3U werden nur EPG-IDs, Sendernamen, Logos und Enigma2-Service-Reference-Hinweise gelesen. Stream-Adressen werden nicht gespeichert oder verwendet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TouchButton(
            onClick = {
                showEpgSourceDialog(
                    context = context,
                    initialUrl = epgState.sourceUrl,
                    onSave = onSaveEpgSource,
                )
            },
        ) {
            Text("EPG-M3U bearbeiten")
        }
        TouchButton(
            onClick = onRefreshEpg,
            enabled = state.channels.isNotEmpty() && !epgState.isRefreshing,
        ) {
            Text(if (epgState.isRefreshing) "EPG wird geladen …" else "EPG jetzt aktualisieren")
        }

        if (state.channels.isNotEmpty()) {
            Text(
                "Senderzuordnung: ${epgState.mappedChannelCount} von ${state.channels.size} · M3U-Sender: ${epgState.sourceChannels.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        epgState.lastUpdatedUtcMillis?.let { updated ->
            Text(
                "XMLTV zuletzt: ${formatDateTime(updated)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        epgState.errorMessage?.let { error ->
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        val unmatched = state.channels.filter { channel ->
            channel.serviceReference in epgState.mappingSuggestions
        }
        if (unmatched.isNotEmpty()) {
            Text("Nicht sicher zugeordnete Sender", style = MaterialTheme.typography.titleMedium)
            Text(
                "Nur eindeutige Treffer werden automatisch übernommen. Unsichere Sender können einmal manuell einer XMLTV-ID zugeordnet werden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            unmatched.take(10).forEach { channel ->
                val suggestions = epgState.mappingSuggestions[channel.serviceReference].orEmpty()
                TouchButton(
                    onClick = {
                        showMappingDialog(
                            context = context,
                            channelName = channel.name,
                            suggestions = suggestions,
                            onSelected = { xmltvId ->
                                onSetEpgMapping(channel.serviceReference, xmltvId)
                            },
                        )
                    },
                    enabled = suggestions.isNotEmpty(),
                ) {
                    Text("Zuordnen: ${channel.name}")
                }
            }
            if (unmatched.size > 10) {
                Text(
                    "+ ${unmatched.size - 10} weitere nicht zugeordnete Sender",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.channels.isNotEmpty()) {
            Text("Sender / EPG Diagnose", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Die XMLTV-ID und die Art der Zuordnung helfen beim Gerätetest, ohne Receiver-Adresse oder Zugangsdaten anzuzeigen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.channels.take(30).forEachIndexed { index, channel ->
                val mapping = mappingByServiceReference[channel.serviceReference]
                Text(
                    text = buildString {
                        append(index + 1)
                        append(". ")
                        append(channel.name)
                        if (mapping != null) {
                            append(" · EPG: ")
                            append(mapping.xmltvChannelId)
                            append(" (")
                            append(mappingMethodLabel(mapping.matchMethod))
                            append(")")
                        } else {
                            append(" · EPG: nicht zugeordnet")
                        }
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
                "Noch keine Sender geladen. Starte „Gigablue aktualisieren“.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Sicherheit: Receiver-Zugangsdaten bleiben lokal und werden nie in Diagnose/Logs ausgegeben. Externe EPG-Quellen erhalten keine Gigablue-Zugangsdaten.",
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

private fun showEpgSourceDialog(
    context: Context,
    initialUrl: String,
    onSave: (String) -> Unit,
) {
    val input = EditText(context).apply {
        setText(initialUrl)
        hint = "https://…/tv.m3u"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
    }
    val padding = (20 * context.resources.displayMetrics.density).toInt()
    input.setPadding(padding, padding / 2, padding, 0)

    AlertDialog.Builder(context)
        .setTitle("EPG-M3U")
        .setMessage("Die M3U muss x-tvg-url sowie tvg-id/tvg-name enthalten. Wiedergabe-URLs der M3U werden ignoriert.")
        .setView(input)
        .setPositiveButton("Speichern") { _, _ -> onSave(input.text.toString()) }
        .setNegativeButton("Abbrechen", null)
        .show()
}

private fun showMappingDialog(
    context: Context,
    channelName: String,
    suggestions: List<EpgSourceChannel>,
    onSelected: (String?) -> Unit,
) {
    val labels = buildList {
        add("Automatisch neu zuordnen")
        suggestions.forEach { source ->
            add("${source.displayName} · ${source.xmltvChannelId}")
        }
    }.toTypedArray()

    AlertDialog.Builder(context)
        .setTitle("EPG für $channelName")
        .setItems(labels) { _, which ->
            if (which == 0) onSelected(null) else onSelected(suggestions[which - 1].xmltvChannelId)
        }
        .setNegativeButton("Abbrechen", null)
        .show()
}

private fun mappingMethodLabel(method: String): String = when (method) {
    EpgChannelMatcher.METHOD_MANUAL -> "manuell"
    EpgChannelMatcher.METHOD_SERVICE_REFERENCE -> "Service-Reference"
    EpgChannelMatcher.METHOD_EXACT_NAME -> "Name exakt"
    EpgChannelMatcher.METHOD_FUZZY_NAME -> "Name sicher"
    EpgChannelMatcher.METHOD_ALT_XMLTV_ID -> "Alternative ID"
    else -> method
}

private fun formatDateTime(utcMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(utcMillis))
