package com.andreassamitsch.ilauncher.data.kodi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
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
        repeat(KODI_RPC_STARTUP_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(KODI_RPC_RETRY_MILLIS)
            val result = runCatching {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(KODI_RPC_HOST, KODI_RPC_PORT),
                        KODI_RPC_CONNECT_TIMEOUT_MILLIS,
                    )
                    socket.soTimeout = KODI_RPC_READ_TIMEOUT_MILLIS
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
                        put("id", 1)
                    }
                    socket.getOutputStream().bufferedWriter().use { writer ->
                        writer.write(request.toString())
                        writer.write("\n")
                        writer.flush()
                    }
                    val response = BufferedReader(InputStreamReader(socket.getInputStream())).readLine().orEmpty()
                    response.contains("\"result\":\"OK\"") || response.contains("\"result\": \"OK\"")
                }
            }.getOrDefault(false)
            if (result) {
                Log.d(TAG, "Opened TMDb Helper search through Kodi JSON-RPC")
                return true
            }
        }
        return false
    }

    private companion object {
        const val KODI_PACKAGE = "org.xbmc.kodi"
        const val KODI_RPC_HOST = "127.0.0.1"
        const val KODI_RPC_PORT = 9090
        const val KODI_RPC_STARTUP_ATTEMPTS = 20
        const val KODI_RPC_RETRY_MILLIS = 250L
        const val KODI_RPC_CONNECT_TIMEOUT_MILLIS = 250
        const val KODI_RPC_READ_TIMEOUT_MILLIS = 900
    }
}

internal fun tmdbHelperSearchPath(query: String): String {
    val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
    return "plugin://plugin.video.themoviedb.helper/?info=search&tmdb_type=both&query=$encoded"
}
