package com.andreassamitsch.joyntv

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Safe diagnostics for proving that proxy and OpenVPN receive the exact same credential bytes. */
internal object JoynNordCredentialFingerprint {
    fun describe(username: String, password: String): String =
        "userBytes=${username.toByteArray(StandardCharsets.UTF_8).size}/sha=${shaPrefix(username)} · " +
            "passBytes=${password.toByteArray(StandardCharsets.UTF_8).size}/sha=${shaPrefix(password)}"

    private fun shaPrefix(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.take(6).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
