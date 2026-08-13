package com.andreassamitsch.ilauncher.data.kodi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.InputStreamReader
import java.io.Reader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "KODI_TMDB_HELPER"

class KodiSearchLauncher(private val context: Context) {
    fun isAvailable(): Boolean = context.packageManager.getLaunchIntentForPackage(KODI_PACKAGE) != null

    fun launch(query: String): Boolean {
        if (!isAvailable()) return false
        val normalizedQuery = query.trim().replace(Regex("\\s+"), " ")
        if (normalizedQuery.isBlank()) return false

        val kodiIntent = context.packageManager.getLaunchIntentForPackage(KODI_PACKAGE) ?: return false
        runCatching {
            kodiIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(kodiIntent)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to launch Kodi (${throwable.javaClass.simpleName})")
            return false
        }

        // Stock Kodi exposes no Android intent that navigates to an arbitrary plugin directory.
        // Kodi's own JSON-RPC TCP server is the supported navigation API when local remote control
        // is enabled. TMDb Helper's current search route accepts info=search, tmdb_type and query.
        thread(name = "i-launcher-kodi-tmdb-helper") {
            val pluginPath = tmdbHelperSearchPath(normalizedQuery)
            val success = activateKodiVideoWindow(pluginPath)
            if (!success) {
                Log.w(TAG, "Kodi JSON-RPC unavailable; TMDb Helper search could not be opened")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "Kodi: Unter Dienste > Steuerung die Fernsteuerung durch Programme auf diesem System aktivieren.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        return true
    }

    private fun activateKodiVideoWindow(pluginPath: String): Boolean {
        val socket = connectToKodiJsonRpc() ?: return false
        return socket.use { connectedSocket ->
            runCatching {
                sendActivateWindowRequest(connectedSocket, pluginPath)
            }.getOrDefault(false)
        }
    }

    /**
     * Kodi may need a short moment after Android launches its activity before the local JSON-RPC
     * listener is reachable. Retry only the TCP connection. Once a connection succeeds, the GUI
     * command itself is sent exactly once so an ambiguous/late response can never re-run a search.
     */
    private fun connectToKodiJsonRpc(): Socket? {
        repeat(KODI_RPC_STARTUP_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(KODI_RPC_RETRY_MILLIS)
            val socket = Socket()
            val connected = runCatching {
                socket.connect(
                    InetSocketAddress(KODI_RPC_HOST, KODI_RPC_PORT),
                    KODI_RPC_CONNECT_TIMEOUT_MILLIS,
                )
                socket.soTimeout = KODI_RPC_READ_TIMEOUT_MILLIS
            }.isSuccess
            if (connected) return socket
            runCatching { socket.close() }
        }
        return null
    }

    private fun sendActivateWindowRequest(socket: Socket, pluginPath: String): Boolean {
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "GUI.ActivateWindow")
            put(
                "params",
                JSONObject().apply {
                    put("window", "videos")
                    put("parameters", JSONArray().put(pluginPath).put("return"))
                },
            )
            put("id", KODI_RPC_REQUEST_ID)
        }
        val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
        writer.write(request.toString())
        writer.flush()

        // Raw Kodi JSON-RPC/TCP does not delimit messages with line breaks. Notifications may also
        // arrive before our response, so read complete JSON objects and wait for our request id.
        val reader = InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        repeat(KODI_RPC_MAX_RESPONSE_MESSAGES) {
            val response = readKodiJsonMessage(reader) ?: return false
            val json = JSONObject(response)
            if (json.optInt("id", -1) != KODI_RPC_REQUEST_ID) return@repeat
            return json.optString("result") == "OK"
        }
        return false
    }

    private companion object {
        const val KODI_PACKAGE = "org.xbmc.kodi"
        const val KODI_RPC_HOST = "127.0.0.1"
        const val KODI_RPC_PORT = 9090
        const val KODI_RPC_REQUEST_ID = 1
        const val KODI_RPC_STARTUP_ATTEMPTS = 20
        const val KODI_RPC_MAX_RESPONSE_MESSAGES = 16
        const val KODI_RPC_RETRY_MILLIS = 250L
        const val KODI_RPC_CONNECT_TIMEOUT_MILLIS = 250
        const val KODI_RPC_READ_TIMEOUT_MILLIS = 900
    }
}

/**
 * Reads one object from Kodi's delimiter-free raw JSON-RPC stream. Curly braces inside quoted
 * strings do not affect framing, and the reader remains positioned at the next message.
 */
internal fun readKodiJsonMessage(reader: Reader): String? {
    val message = StringBuilder()
    var started = false
    var depth = 0
    var inString = false
    var escaped = false

    while (true) {
        val value = reader.read()
        if (value == -1) return null
        val char = value.toChar()

        if (!started) {
            if (char.isWhitespace()) continue
            if (char != '{') continue
            started = true
            depth = 1
            message.append(char)
            continue
        }

        message.append(char)
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }

        when (char) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return message.toString()
            }
        }
    }
}

internal fun tmdbHelperSearchPath(query: String): String {
    val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
    return "plugin://plugin.video.themoviedb.helper/?info=search&tmdb_type=both&query=$encoded"
}
