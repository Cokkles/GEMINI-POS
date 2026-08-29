package com.cokkles.gpos.platform.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreCredentialStore(
    context: Context,
) : CredentialStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun read(): StoredCredential? {
        val encoded = preferences.getString(KEY_CREDENTIAL_BLOB, null) ?: return null
        return runCatching {
            val plaintext = decrypt(encoded)
            val json = JSONObject(plaintext)
            StoredCredential(
                idToken = json.getString("id_token"),
                expiresAtEpochMs = json.optLong("expires_at_epoch_ms").takeIf { it > 0L },
            )
        }.getOrElse {
            clear()
            null
        }
    }

    override suspend fun replace(credential: StoredCredential) {
        val payload = JSONObject()
            .put("id_token", credential.idToken)
            .apply {
                credential.expiresAtEpochMs?.let { put("expires_at_epoch_ms", it) }
            }
            .toString()

        preferences.edit()
            .putString(KEY_CREDENTIAL_BLOB, encrypt(payload))
            .apply()
    }

    override suspend fun clear() {
        preferences.edit().remove(KEY_CREDENTIAL_BLOB).apply()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$iv.$body"
    }

    private fun decrypt(encoded: String): String {
        val separator = encoded.indexOf('.')
        require(separator > 0 && separator < encoded.lastIndex) { "Invalid encrypted credential envelope." }
        val iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP)
        val ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "gpos_secure_session"
        const val KEY_CREDENTIAL_BLOB = "credential_blob"
        const val KEY_ALIAS = "gpos_auth1_session_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
