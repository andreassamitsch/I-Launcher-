package com.andreassamitsch.joyntv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.kape.openvpn.data.models.OpenVpnServerPeerInformation
import com.kape.openvpn.domain.usecases.IOpenVpnMtuTestResultAnnouncer
import com.kape.openvpn.presenters.OpenVpnAPI
import com.kape.openvpn.presenters.OpenVpnBuilder
import com.kape.openvpn.presenters.OpenVpnProcessEventHandler
import com.kape.openvpn.presenters.OpenVpnState
import com.kape.openvpn.presenters.OpenVpnUserCredentials
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Experimental NordVPN OpenVPN tunnel scoped to this Joyn TV package only.
 *
 * The OpenVPN transport socket is protected from the Android VPN before the TUN interface is
 * established, while Builder.addAllowedApplication(packageName) makes sure no other TV app is
 * routed through NordVPN.
 */
internal class JoynNordOpenVpnService : VpnService(), OpenVpnProcessEventHandler {
    private lateinit var openVpn: OpenVpnAPI
    private var currentHost: String? = null
    private var currentPeerAddress: String = ""
    private var username: String = ""
    private var password: String = ""
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        openVpn = OpenVpnBuilder()
            .setContext(applicationContext)
            .setClientCoroutineContext(Dispatchers.Main.immediate)
            .setOpenVpnMtuTestResultAnnouncer(object : IOpenVpnMtuTestResultAnnouncer {
                override fun onMtuTestResult(localToRemote: Int, remoteToLocal: Int) {
                    JoynNordTunnelRuntime.log("MTU-Test $localToRemote/$remoteToLocal")
                }
            })
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startTunnel(intent)
            ACTION_DISCONNECT -> stopTunnel(stopSelfAfter = true)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        JoynNordTunnelRuntime.log("Android hat die VPN-Berechtigung widerrufen")
        stopTunnel(stopSelfAfter = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching {
            openVpn.stop { }
        }
        currentHost = null
        currentPeerAddress = ""
        username = ""
        password = ""
        JoynNordTunnelRuntime.update(JoynNordTunnelState.Idle)
        super.onDestroy()
    }

    private fun startTunnel(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val remoteHost = intent.getStringExtra(EXTRA_REMOTE_HOST).orEmpty()
        val remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, 443)
        val protocol = intent.getStringExtra(EXTRA_PROTOCOL).orEmpty().ifBlank { "tcp-client" }
        val caPath = intent.getStringExtra(EXTRA_CA_PATH).orEmpty()
        val tlsKeyPath = intent.getStringExtra(EXTRA_TLS_KEY_PATH)?.takeIf(String::isNotBlank)
        val tlsMode = intent.getStringExtra(EXTRA_TLS_MODE)
            ?.let { runCatching { JoynNordOpenVpnTlsMode.valueOf(it) }.getOrNull() }
        val keyDirection = intent.getStringExtra(EXTRA_KEY_DIRECTION)?.takeIf(String::isNotBlank)
        val verifyX509 = intent.getStringArrayListExtra(EXTRA_VERIFY_X509)?.toList().orEmpty()
        val auth = intent.getStringExtra(EXTRA_AUTH).orEmpty().ifBlank { "SHA512" }
        val cipher = intent.getStringExtra(EXTRA_CIPHER).orEmpty().ifBlank { "AES-256-CBC" }
        val dataCiphers = intent.getStringExtra(EXTRA_DATA_CIPHERS).orEmpty()
            .ifBlank { "AES-256-GCM:AES-128-GCM:$cipher" }
        val tlsCipher = intent.getStringExtra(EXTRA_TLS_CIPHER)?.takeIf(String::isNotBlank)
        val tunMtu = intent.getIntExtra(EXTRA_TUN_MTU, 1500)
        val mssFix = intent.getIntExtra(EXTRA_MSS_FIX, -1).takeIf { it > 0 }
        val newUsername = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val newPassword = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()

        if (host.isBlank() || remoteHost.isBlank() || caPath.isBlank() ||
            newUsername.isBlank() || newPassword.isBlank()
        ) {
            JoynNordTunnelRuntime.update(
                JoynNordTunnelState.Error(host.ifBlank { null }, "OpenVPN-Startdaten unvollständig"),
            )
            stopSelf()
            return
        }

        currentHost = host
        currentPeerAddress = ""
        username = newUsername
        password = newPassword
        stopping = false
        JoynNordTunnelRuntime.clearLogs()
        JoynNordTunnelRuntime.update(JoynNordTunnelState.Connecting(host))
        startForeground(NOTIFICATION_ID, buildNotification("NordVPN · $host wird verbunden"))

        val managementSocket = File(filesDir, MANAGEMENT_SOCKET_NAME).apply { delete() }.absolutePath
        val tempDirectory = File(cacheDir, "nord_openvpn_tmp").apply { mkdirs() }.absolutePath

        val params = mutableListOf(
            "--status-version", "3",
            "--machine-readable-output",
            "--management-query-passwords",
            "--management-forget-disconnect",
            "--management-hold",
            "--management", managementSocket, "unix",
            "--tmp-dir", tempDirectory,
            "--ca", caPath,
            "--remote", remoteHost, remotePort.toString(),
            "--dev", "tun",
            "--auth-user-pass",
            "--client",
            "--proto", protocol,
            "--connect-retry", "2", "5",
            "--connect-retry-max", "1",
            "--resolv-retry", "5",
            "--persist-key",
            "--persist-tun",
            "--nobind",
            "--data-ciphers", dataCiphers,
            "--cipher", cipher,
            "--auth", auth,
            "--auth-nocache",
            "--remote-cert-tls", "server",
            "--tun-mtu", tunMtu.toString(),
            "--reneg-sec", "0",
            "--pull",
            "--fast-io",
            "--comp-lzo", "no",
            "--ping", "15",
            "--ping-restart", "0",
            "--ping-timer-rem",
            "--verb", "3",
            "--mute-replay-warnings",
        )

        mssFix?.let { params += listOf("--mssfix", it.toString()) }
        tlsCipher?.let { params += listOf("--tls-cipher", it) }
        if (verifyX509.isNotEmpty()) {
            params += "--verify-x509-name"
            params += verifyX509
        }
        if (tlsKeyPath != null && tlsMode != null) {
            when (tlsMode) {
                JoynNordOpenVpnTlsMode.AUTH -> {
                    params += listOf("--tls-auth", tlsKeyPath)
                    keyDirection?.let { params += it }
                }
                JoynNordOpenVpnTlsMode.CRYPT -> params += listOf("--tls-crypt", tlsKeyPath)
            }
        }

        JoynNordTunnelRuntime.log(
            "OpenVPN start $host → $remoteHost:$remotePort $protocol · MTU=$tunMtu · auth=$auth · cipher=$cipher",
        )
        openVpn.start(params, this) { result ->
            result.exceptionOrNull()?.let { error ->
                val detail = summarize(error)
                JoynNordTunnelRuntime.log("OpenVPN-Prozessstart fehlgeschlagen: $detail")
                JoynNordTunnelRuntime.update(JoynNordTunnelState.Error(host, detail))
            }
        }
    }

    private fun stopTunnel(stopSelfAfter: Boolean) {
        val host = currentHost
        stopping = true
        JoynNordTunnelRuntime.update(JoynNordTunnelState.Stopping(host))
        if (!::openVpn.isInitialized) {
            JoynNordTunnelRuntime.update(JoynNordTunnelState.Idle)
            if (stopSelfAfter) stopSelf()
            return
        }
        openVpn.stop { result ->
            result.exceptionOrNull()?.let { JoynNordTunnelRuntime.log("OpenVPN stop: ${summarize(it)}") }
            currentHost = null
            currentPeerAddress = ""
            username = ""
            password = ""
            stopping = false
            JoynNordTunnelRuntime.update(JoynNordTunnelState.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopSelfAfter) stopSelf()
        }
    }

    override fun serviceProtect(fd: Int): Result<Boolean> = runCatching { protect(fd) }

    override fun serviceEstablish(serverPeerInformation: OpenVpnServerPeerInformation): Result<Int> = runCatching {
        val (address, prefix) = parseAddress(serverPeerInformation.address)
        val builder = Builder()
            .setSession("Joyn NordVPN · ${currentHost.orEmpty()}")
            .setBlocking(true)
            .setMtu(DEFAULT_TUN_MTU)
            .addAddress(address, prefix)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(NORD_DNS_1)
            .addDnsServer(NORD_DNS_2)

        // Critical split-tunnel rule: only this APK enters the Android VPN. Other TV apps and the
        // launcher keep using their normal network route.
        builder.addAllowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) builder.setUnderlyingNetworks(null)

        val descriptor = builder.establish()
            ?: error("Android VpnService.Builder.establish() lieferte null")
        currentPeerAddress = serverPeerInformation.address
        JoynNordTunnelRuntime.log(
            "TUN etabliert: ${serverPeerInformation.address}, Gateway=${serverPeerInformation.gateway}, nur $packageName",
        )
        descriptor.detachFd()
    }

    override fun getUserCredentials(): Result<OpenVpnUserCredentials> =
        Result.success(OpenVpnUserCredentials(username, password))

    override fun stateUpdated(state: OpenVpnState): Result<Unit> {
        val host = currentHost
        JoynNordTunnelRuntime.log("OpenVPN state=${state.javaClass.simpleName}")
        when (state) {
            OpenVpnState.Connected -> {
                if (host != null) {
                    JoynNordTunnelRuntime.update(JoynNordTunnelState.Connected(host, currentPeerAddress))
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, buildNotification("NordVPN verbunden · $host"))
                }
            }
            OpenVpnState.Reconnecting -> if (host != null && !stopping) {
                JoynNordTunnelRuntime.update(JoynNordTunnelState.Connecting(host))
            }
            OpenVpnState.Exiting -> if (!stopping) {
                JoynNordTunnelRuntime.update(
                    JoynNordTunnelState.Error(host, "OpenVPN-Verbindung wurde beendet"),
                )
            }
            else -> Unit
        }
        return Result.success(Unit)
    }

    override fun processByteCountReceived(tx: Long, rx: Long): Result<Unit> = Result.success(Unit)

    override fun openVpnProcessOutputLineReceived(line: String): Result<Unit> {
        JoynNordTunnelRuntime.log(line)
        val host = currentHost
        when {
            line.contains("AUTH_FAILED", ignoreCase = true) -> {
                JoynNordTunnelRuntime.update(JoynNordTunnelState.Error(host, "NordVPN OpenVPN AUTH_FAILED"))
            }
            line.contains("Options error", ignoreCase = true) ||
                line.contains("Unrecognized option", ignoreCase = true) -> {
                JoynNordTunnelRuntime.update(
                    JoynNordTunnelState.Error(host, line.replace('\n', ' ').replace('\r', ' ').take(220)),
                )
            }
        }
        return Result.success(Unit)
    }

    private fun parseAddress(value: String): Pair<String, Int> {
        val normalized = value.trim().substringBefore(' ')
        val address = normalized.substringBefore('/')
        val prefix = normalized.substringAfter('/', "32").toIntOrNull() ?: 32
        require(address.isNotBlank()) { "OpenVPN lieferte keine TUN-Adresse" }
        return address to prefix
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Joyn NordVPN",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        val configureIntent = Intent(this, ProxySettingsActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            configureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_joyn_tv)
            .setContentTitle("Joyn · NordVPN")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun summarize(error: Throwable): String {
        val message = error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').trim().take(180)
        return if (message.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

    companion object {
        private const val ACTION_CONNECT = "com.andreassamitsch.joyntv.NORD_OPENVPN_CONNECT"
        private const val ACTION_DISCONNECT = "com.andreassamitsch.joyntv.NORD_OPENVPN_DISCONNECT"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_REMOTE_HOST = "remote_host"
        private const val EXTRA_REMOTE_PORT = "remote_port"
        private const val EXTRA_PROTOCOL = "protocol"
        private const val EXTRA_CA_PATH = "ca_path"
        private const val EXTRA_TLS_KEY_PATH = "tls_key_path"
        private const val EXTRA_TLS_MODE = "tls_mode"
        private const val EXTRA_KEY_DIRECTION = "key_direction"
        private const val EXTRA_VERIFY_X509 = "verify_x509"
        private const val EXTRA_AUTH = "auth"
        private const val EXTRA_CIPHER = "cipher"
        private const val EXTRA_DATA_CIPHERS = "data_ciphers"
        private const val EXTRA_TLS_CIPHER = "tls_cipher"
        private const val EXTRA_TUN_MTU = "tun_mtu"
        private const val EXTRA_MSS_FIX = "mss_fix"
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_PASSWORD = "password"

        private const val NOTIFICATION_CHANNEL_ID = "joyn_nord_openvpn"
        private const val NOTIFICATION_ID = 21089
        private const val MANAGEMENT_SOCKET_NAME = "joyn-openvpn-management"
        private const val DEFAULT_TUN_MTU = 1500
        private const val NORD_DNS_1 = "103.86.96.100"
        private const val NORD_DNS_2 = "103.86.99.100"

        fun connect(context: Context, profile: JoynNordOpenVpnProfile, username: String, password: String) {
            val intent = Intent(context, JoynNordOpenVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_HOST, profile.hostname)
                .putExtra(EXTRA_REMOTE_HOST, profile.remoteHost)
                .putExtra(EXTRA_REMOTE_PORT, profile.remotePort)
                .putExtra(EXTRA_PROTOCOL, profile.protocol)
                .putExtra(EXTRA_CA_PATH, profile.caPath)
                .putExtra(EXTRA_TLS_KEY_PATH, profile.tlsKeyPath)
                .putExtra(EXTRA_TLS_MODE, profile.tlsMode?.name)
                .putExtra(EXTRA_KEY_DIRECTION, profile.keyDirection)
                .putStringArrayListExtra(EXTRA_VERIFY_X509, ArrayList(profile.verifyX509Args))
                .putExtra(EXTRA_AUTH, profile.auth)
                .putExtra(EXTRA_CIPHER, profile.cipher)
                .putExtra(EXTRA_DATA_CIPHERS, profile.dataCiphers)
                .putExtra(EXTRA_TLS_CIPHER, profile.tlsCipher)
                .putExtra(EXTRA_TUN_MTU, profile.tunMtu)
                .putExtra(EXTRA_MSS_FIX, profile.mssFix ?: -1)
                .putExtra(EXTRA_USERNAME, username)
                .putExtra(EXTRA_PASSWORD, password)
            context.startForegroundService(intent)
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, JoynNordOpenVpnService::class.java).setAction(ACTION_DISCONNECT),
            )
        }
    }
}
