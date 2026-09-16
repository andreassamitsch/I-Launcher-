package com.andreassamitsch.ilauncher.data.joyn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Small cross-process client for the signature-protected bridge in the standalone Joyn TV APK.
 *
 * I Launcher never receives Mysterium credentials. The bridge returns only public Joyn playback
 * URLs plus an ephemeral loopback proxy port that is kept alive by the bound Joyn TV service.
 */
internal class JoynPlaybackBridgeClient(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val requestIds = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

    @Volatile
    private var remote: Messenger? = null
    private var bindDeferred: CompletableDeferred<Messenger>? = null
    private var bound = false

    private val replyMessenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.what != MSG_RESPONSE) return@Handler false
            val data = message.data
            val requestId = data.getInt(KEY_REQUEST_ID, -1)
            pending.remove(requestId)?.complete(data)
            true
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val messenger = service?.let(::Messenger)
            val waiter = synchronized(lock) {
                remote = messenger
                bound = messenger != null
                bindDeferred.also { bindDeferred = null }
            }
            if (messenger != null) {
                waiter?.complete(messenger)
            } else {
                waiter?.completeExceptionally(IOException("Joyn TV bridge returned no binder"))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            disconnect(IOException("Joyn TV bridge disconnected"), clearBound = false)
        }

        override fun onBindingDied(name: ComponentName?) {
            disconnect(IOException("Joyn TV bridge binding died"), clearBound = true)
        }

        override fun onNullBinding(name: ComponentName?) {
            disconnect(IOException("Joyn TV bridge is unavailable"), clearBound = true)
        }
    }

    suspend fun listChannels(): List<JoynBridgeChannel> {
        val response = request(MSG_LIST_CHANNELS, Bundle(), INVENTORY_TIMEOUT_MILLIS)
        @Suppress("DEPRECATION")
        val rows = response.getParcelableArrayList<Bundle>(KEY_CHANNELS).orEmpty()
        return rows.mapNotNull { row ->
            val id = row.getString(KEY_CHANNEL_ID)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = row.getString(KEY_CHANNEL_TITLE)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            JoynBridgeChannel(
                id = id,
                title = title,
                country = row.getString(KEY_COUNTRY).orEmpty(),
                quality = row.getString(KEY_QUALITY),
            )
        }
    }

    suspend fun resolvePlayback(channel: JoynBridgeChannel): JoynFallbackPlayback {
        val response = request(
            what = MSG_RESOLVE_PLAYBACK,
            payload = Bundle().apply { putString(KEY_CHANNEL_ID, channel.id) },
            timeoutMillis = PLAYBACK_TIMEOUT_MILLIS,
        )
        val manifest = response.getString(KEY_MANIFEST_URL)?.takeIf(String::isNotBlank)
            ?: throw IOException("Joyn TV bridge returned no DASH manifest")
        val proxyHost = response.getString(KEY_PROXY_HOST)?.takeIf(String::isNotBlank)
            ?: throw IOException("Joyn TV bridge returned no proxy host")
        val proxyPort = response.getInt(KEY_PROXY_PORT, 0)
        if (proxyPort !in 1..65535) throw IOException("Joyn TV bridge returned an invalid proxy port")

        return JoynFallbackPlayback(
            channelId = channel.id,
            channelTitle = channel.title,
            country = channel.country,
            manifestUrl = manifest,
            licenseUrl = response.getString(KEY_LICENSE_URL)?.takeIf(String::isNotBlank),
            certificateUrl = response.getString(KEY_CERTIFICATE_URL)?.takeIf(String::isNotBlank),
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            bridgeGeneration = response.getLong(KEY_BRIDGE_GENERATION, 0L),
        )
    }

    fun releasePlayback() {
        val target = remote ?: return
        val requestId = requestIds.getAndIncrement()
        runCatching {
            target.send(
                Message.obtain(null, MSG_RELEASE_PLAYBACK).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply { putInt(KEY_REQUEST_ID, requestId) }
                },
            )
        }
    }

    private suspend fun request(what: Int, payload: Bundle, timeoutMillis: Long): Bundle {
        val target = ensureBound()
        val requestId = requestIds.getAndIncrement()
        val response = CompletableDeferred<Bundle>()
        pending[requestId] = response
        try {
            target.send(
                Message.obtain(null, what).apply {
                    replyTo = replyMessenger
                    data = Bundle(payload).apply { putInt(KEY_REQUEST_ID, requestId) }
                },
            )
        } catch (error: RemoteException) {
            pending.remove(requestId)
            throw IOException("Joyn TV bridge request failed", error)
        }

        val result = try {
            withTimeout(timeoutMillis) { response.await() }
        } catch (timeout: TimeoutCancellationException) {
            pending.remove(requestId)
            throw IOException("Zeitüberschreitung beim Joyn TV bridge", timeout)
        }
        if (!result.getBoolean(KEY_OK, false)) {
            throw IOException(result.getString(KEY_ERROR).orEmpty().ifBlank { "Joyn TV bridge failed" })
        }
        return result
    }

    private suspend fun ensureBound(): Messenger {
        remote?.let { return it }

        var shouldBind = false
        val waiter = synchronized(lock) {
            remote?.let { return it }
            bindDeferred ?: CompletableDeferred<Messenger>().also {
                bindDeferred = it
                shouldBind = true
            }
        }

        if (shouldBind) {
            val intent = Intent().setComponent(
                ComponentName(JOYN_PACKAGE, "$JOYN_PACKAGE.JoynPlaybackBridgeService"),
            )
            val started = runCatching {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!started) {
                val error = IOException("Joyn TV ist nicht installiert oder der Playback-Bridge fehlt")
                synchronized(lock) {
                    if (bindDeferred === waiter) bindDeferred = null
                    bound = false
                }
                waiter.completeExceptionally(error)
            } else {
                synchronized(lock) { bound = true }
            }
        }

        return try {
            withTimeout(BIND_TIMEOUT_MILLIS) { waiter.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw IOException("Joyn TV bridge konnte nicht verbunden werden", timeout)
        }
    }

    private fun disconnect(error: IOException, clearBound: Boolean) {
        val bindingWaiter = synchronized(lock) {
            remote = null
            if (clearBound) bound = false
            bindDeferred.also { bindDeferred = null }
        }
        bindingWaiter?.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        releasePlayback()
        val shouldUnbind = synchronized(lock) {
            val value = bound
            bound = false
            remote = null
            bindDeferred?.cancel()
            bindDeferred = null
            value
        }
        pending.values.forEach { it.cancel() }
        pending.clear()
        if (shouldUnbind) runCatching { appContext.unbindService(connection) }
    }

    companion object {
        private const val JOYN_PACKAGE = "com.andreassamitsch.joyntv"

        private const val MSG_LIST_CHANNELS = 1
        private const val MSG_RESOLVE_PLAYBACK = 2
        private const val MSG_RELEASE_PLAYBACK = 3
        private const val MSG_RESPONSE = 100

        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_OK = "ok"
        private const val KEY_ERROR = "error"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_CHANNEL_TITLE = "channel_title"
        private const val KEY_COUNTRY = "country"
        private const val KEY_QUALITY = "quality"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_LICENSE_URL = "license_url"
        private const val KEY_CERTIFICATE_URL = "certificate_url"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_BRIDGE_GENERATION = "bridge_generation"

        private const val BIND_TIMEOUT_MILLIS = 5_000L
        private const val INVENTORY_TIMEOUT_MILLIS = 90_000L
        private const val PLAYBACK_TIMEOUT_MILLIS = 45_000L
    }
}

internal data class JoynBridgeChannel(
    val id: String,
    val title: String,
    val country: String,
    val quality: String? = null,
)

internal data class JoynFallbackPlayback(
    val channelId: String,
    val channelTitle: String,
    val country: String,
    val manifestUrl: String,
    val licenseUrl: String?,
    val certificateUrl: String?,
    val proxyHost: String,
    val proxyPort: Int,
    val bridgeGeneration: Long,
)
