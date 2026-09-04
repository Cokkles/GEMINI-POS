package com.cokkles.gpos.data.workspace

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cokkles.gpos.platform.security.StoredCredential
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted account-keyed documents. Never silently replace an unreadable draft. Call on IO. */
class ProtectedWorkspaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aegis_workspace_v1", Context.MODE_PRIVATE)
    fun read(owner: String, document: String): String? = synchronized(lock) {
        val name = name(owner, document)
        val raw = prefs.getString(name, null) ?: return@synchronized null
        val parts = raw.split('.')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(name.toByteArray())
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun write(owner: String, document: String, text: String) = synchronized(lock) {
        val name = name(owner, document)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(name, encoded).commit()) { "Local save failed. Keep this screen open and copy your draft." }
    }
    private fun name(owner: String, document: String): String {
        require(owner.isNotBlank())
        return MessageDigest.getInstance("SHA-256").digest(owner.lowercase().toByteArray()).joinToString("") { "%02x".format(it) } + ":" + document
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("aegis_workspace_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("aegis_workspace_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }
    companion object { private val lock = Any() }
}

/** Used only to partition previously validated local credentials, never to authorize backend calls. */
fun StoredCredential.workspaceOwner(): String = runCatching {
    val body = String(Base64.decode(idToken.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    org.json.JSONObject(body).optString("email").trim().lowercase()
}.getOrDefault("")
