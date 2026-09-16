package com.andreassamitsch.joyntv

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Signature-protected bridge used by I Launcher for seamless Joyn Live-TV fallback.
 *
 * The standalone Joyn TV APK remains the owner of its Mysterium account, residential leases and
 * Joyn protocol state. I Launcher deliberately does not copy those credentials into its own app
 * storage. Instead it asks this bound service for the currently available live-channel inventory
 * and, on fallback, for one resolved DASH/Widevine playback route.
 *
 * Playback media itself is still rendered by I Launcher's Media3 player. For that purpose the
 * service keeps a dedicated loopback CONNECT bridge alive for the lifetime of the binding and
 * returns its ephemeral port together with the manifest/license URLs. The bridge is configured with
 * the exact Mysterium lease selected for the requested country, so later background work in the
 * standalone Joyn app cannot silently move I Launcher's already running media path to another
 * country. The service is exported only behind a signature permission; both APKs are published with
 * the repository's permanent signing key.
 */
class JoynPlaybackBridgeService : Service(), Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: JoynRepository
    private lateinit var proxySettings: JoynProxySettings

    private val bridgeLock = Any()
    private var externalBridge: JoynMysteriumProxyBridge? = null
    private var externalBridgeKey: String? = null
    private val bridgeGeneration = AtomicLong(0L)

    private val incoming = Messenger(
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                val reply = message.replyTo ?: return
                val requestId = message.data.getInt(KEY_REQUEST_ID, -1)
                when (message.what) {
                    MSG_LIST_CHANNELS -> scope.launch {
                        replySafely(reply, requestId, runCatching { channelInventory() })
                    }

                    MSG_RESOLVE_PLAYBACK -> {
                        val channelId = message.data.getString(KEY_CHANNEL_ID).orEmpty()
                        scope.launch {
                            replySafely(
                                reply,
                                requestId,
                                runCatching { resolvePlayback(channelId) },
                            )
                        }
                    }

                    MSG_RELEASE_PLAYBACK -> {
                        closeExternalBridge()
                        replySafely(reply, requestId, Result.success(Bundle()))
                    }

                    else -> super.handleMessage(message)
                }
            }
        },
    )

    override fun onCreate() {
        super.onCreate()
        repository = JoynRepository(applicationContext)
        proxySettings = JoynProxySettings(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        closeExternalBridge()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        close()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun channelInventory(): Bundle {
        val rows = repository.loadLiveTvRowsAndPublish()
        val channels = ArrayList<Bundle>()
        rows.byCountry.forEach { (country, countryChannels) ->
            countryChannels.forEach { channel ->
                channels += Bundle().apply {
                    putString(KEY_CHANNEL_ID, channel.id)
                    putString(KEY_CHANNEL_TITLE, channel.title)
                    putString(KEY_COUNTRY, country.name)
                    putString(KEY_QUALITY, channel.quality)
                }
            }
        }
        return Bundle().apply {
            putParcelableArrayList(KEY_CHANNELS, channels)
        }
    }

    private suspend fun resolvePlayback(channelId: String): Bundle {
        require(channelId.isNotBlank()) { "Joyn channel id is missing" }

        // Prepare the exact country first. The route is pinned only for entitlement/playlist
        // resolution. I Launcher's media transport afterwards uses a dedicated bridge with the
        // captured lease and is therefore independent of later process-wide Joyn route changes.
        repository.prepareLiveChannel(channelId).getOrThrow()
        val preparedProxy = repository.proxyConfig()
        if (!preparedProxy.isUsable || !preparedProxy.isMysterium) {
            error(
                "Für den I-Launcher-Fallback ist ein aktiver Mysterium Residential HTTP-Proxy " +
                    "für diesen Joyn-Markt erforderlich.",
            )
        }

        val pinned = proxySettings.beginPlaybackRoutePin(preparedProxy)
        val playback = try {
            repository.resolveLivePlayback(channelId)
        } finally {
            if (pinned) proxySettings.endPlaybackRoutePin()
        }

        val bridgeAddress = externalBridge(preparedProxy)
        return Bundle().apply {
            putString(KEY_CHANNEL_ID, channelId)
            putString(KEY_MANIFEST_URL, playback.manifestUrl)
            putString(KEY_LICENSE_URL, playback.licenseUrl)
            putString(KEY_CERTIFICATE_URL, playback.certificateUrl)
            putString(KEY_PROXY_HOST, bridgeAddress.hostString)
            putInt(KEY_PROXY_PORT, bridgeAddress.port)
            putLong(KEY_BRIDGE_GENERATION, bridgeGeneration.get())
        }
    }

    private fun externalBridge(config: JoynProxyConfig): java.net.InetSocketAddress = synchronized(bridgeLock) {
        val key = "${config.host.trim()}:${config.port}:${config.username}:${config.password}"
        if (externalBridge == null || externalBridgeKey != key) {
            externalBridge?.close()
            externalBridge = JoynMysteriumProxyBridge(
                remoteHost = config.host.trim(),
                remotePort = config.port,
                username = config.username,
                password = config.password,
                onFailure = { /* I Launcher sees the failed CONNECT directly and can surface it. */ },
            )
            externalBridgeKey = key
            bridgeGeneration.incrementAndGet()
        }
        requireNotNull(externalBridge).localAddress
    }

    private fun closeExternalBridge() = synchronized(bridgeLock) {
        externalBridge?.close()
        externalBridge = null
        externalBridgeKey = null
    }

    private fun replySafely(reply: Messenger, requestId: Int, result: Result<Bundle>) {
        val payload = result.fold(
            onSuccess = { value -> Bundle(value).apply { putBoolean(KEY_OK, true) } },
            onFailure = { error ->
                Bundle().apply {
                    putBoolean(KEY_OK, false)
                    putString(
                        KEY_ERROR,
                        (error.message ?: error.javaClass.simpleName)
                            .replace('\n', ' ')
                            .take(MAX_ERROR_LENGTH),
                    )
                }
            },
        ).apply {
            putInt(KEY_REQUEST_ID, requestId)
        }
        try {
            reply.send(Message.obtain(null, MSG_RESPONSE).apply { data = payload })
        } catch (_: RemoteException) {
            // Caller disappeared while the network operation was in flight.
        }
    }

    override fun close() {
        closeExternalBridge()
    }

    companion object {
        const val PERMISSION = "com.andreassamitsch.joyntv.permission.PLAYBACK_BRIDGE"

        const val MSG_LIST_CHANNELS = 1
        const val MSG_RESOLVE_PLAYBACK = 2
        const val MSG_RELEASE_PLAYBACK = 3
        const val MSG_RESPONSE = 100

        const val KEY_REQUEST_ID = "request_id"
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val KEY_CHANNELS = "channels"
        const val KEY_CHANNEL_ID = "channel_id"
        const val KEY_CHANNEL_TITLE = "channel_title"
        const val KEY_COUNTRY = "country"
        const val KEY_QUALITY = "quality"
        const val KEY_MANIFEST_URL = "manifest_url"
        const val KEY_LICENSE_URL = "license_url"
        const val KEY_CERTIFICATE_URL = "certificate_url"
        const val KEY_PROXY_HOST = "proxy_host"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_BRIDGE_GENERATION = "bridge_generation"

        private const val MAX_ERROR_LENGTH = 500
    }
}
