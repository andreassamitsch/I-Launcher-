package com.andreassamitsch.joyntv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local storage for the existing Joyn parental PIN.
 *
 * The PIN is never stored in plain SharedPreferences. It is encrypted with an AES key
 * generated inside AndroidKeyStore and is only decrypted when Joyn requires it for an
 * entitlement request.
 */
internal class JoynParentalPinSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    fun autoUse(): Boolean = prefs.getBoolean(KEY_AUTO_USE, true) && hasPin()

    fun save(pin: String, autoUse: Boolean = true) {
        require(pin.matches(PIN_REGEX)) { "Der Jugendschutz-PIN muss genau 4 Ziffern haben." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putBoolean(KEY_AUTO_USE, autoUse)
            .apply()
    }

    fun setAutoUse(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_USE, enabled).apply()
    }

    fun readPin(): String? {
        val encrypted = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                Charsets.UTF_8,
            ).takeIf { it.matches(PIN_REGEX) }
        }.getOrNull()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .remove(KEY_AUTO_USE)
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "joyn_parental_pin"
        private const val KEY_ALIAS = "joyn_parental_pin_aes_v1"
        private const val KEY_CIPHERTEXT = "pin_ciphertext"
        private const val KEY_IV = "pin_iv"
        private const val KEY_AUTO_USE = "auto_use"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val PIN_REGEX = Regex("^\\d{4}$")
    }
}
