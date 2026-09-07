package com.cokkles.gpos.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

enum class LocalReceiptState {
    SENDING,
    CONFIRMED,
    QUEUED,
    FAILED,
    CANCELLED,
}

data class PendingTaskMutation(
    val id: String,
    val taskId: String,
    val title: String,
    val stagedAtEpochMs: Long,
    val syncAfterEpochMs: Long,
    val attempts: Int = 0,
    val taskListId: String = "@default",
    val owner: String = "",
)

data class LocalReceipt(
    val id: String,
    val kind: String,
    val summary: String,
    val state: LocalReceiptState,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val result: String? = null,
    val error: String? = null,
    val payload: String? = null,
    val attempts: Int = 0,
    val nextRetryAtEpochMs: Long? = null,
    val manualRetryAllowed: Boolean = false,
)

data class LocalAlert(
    val id: String,
    val severity: String,
    val title: String,
    val detail: String,
    val createdAtEpochMs: Long,
    val acknowledged: Boolean = false,
)

data class LocalLedger(
    val pendingTasks: List<PendingTaskMutation> = emptyList(),
    val receipts: List<LocalReceipt> = emptyList(),
    val alerts: List<LocalAlert> = emptyList(),
)

/**
 * Small identity-bound local mutation ledger.
 *
 * The payload is AES/GCM encrypted with an Android Keystore key. Callers clear the store on
 * explicit logout. No OAuth token, backend endpoint, document ID, or raw private report is stored.
 */
class ProtectedLocalLedger(
    context: Context,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): LocalLedger = synchronized(lock) { readUnsafe() }

    fun update(transform: (LocalLedger) -> LocalLedger): LocalLedger = synchronized(lock) {
        val updated = transform(readUnsafe()).bounded()
        check(preferences.edit().putString(KEY_LEDGER_BLOB, encrypt(updated.toJson().toString())).commit()) { "Local queue could not be saved." }
        updated
    }

    fun clear() = synchronized(lock) {
        preferences.edit().remove(KEY_LEDGER_BLOB).commit()
        Unit
    }

    private fun readUnsafe(): LocalLedger {
        val encoded = preferences.getString(KEY_LEDGER_BLOB, null) ?: return LocalLedger()
        return runCatching { parseLedger(JSONObject(decrypt(encoded))) }
            .getOrElse {
                preferences.edit().remove(KEY_LEDGER_BLOB).commit()
                LocalLedger()
            }
    }

    private fun LocalLedger.bounded(): LocalLedger = copy(
        pendingTasks = pendingTasks.distinctBy { "${it.owner}/${it.taskListId}/${it.taskId}" }.takeLast(MAX_PENDING_TASKS),
        receipts = receipts.sortedByDescending { it.updatedAtEpochMs }.take(MAX_RECEIPTS),
        alerts = alerts.sortedByDescending { it.createdAtEpochMs }.take(MAX_ALERTS),
    )

    private fun LocalLedger.toJson(): JSONObject = JSONObject()
        .put(
            "pending_tasks",
            JSONArray().apply {
                pendingTasks.forEach { item ->
                    put(
                        JSONObject()
                            .put("id", item.id)
                            .put("task_id", item.taskId)
                            .put("task_list_id", item.taskListId)
                            .put("owner", item.owner)
                            .put("title", item.title)
                            .put("staged_at", item.stagedAtEpochMs)
                            .put("sync_after", item.syncAfterEpochMs)
                            .put("attempts", item.attempts),
                    )
                }
            },
        )
        .put(
            "receipts",
            JSONArray().apply {
                receipts.forEach { item ->
                    put(
                        JSONObject()
                            .put("id", item.id)
                            .put("kind", item.kind)
                            .put("summary", item.summary)
                            .put("state", item.state.name)
                            .put("created_at", item.createdAtEpochMs)
                            .put("updated_at", item.updatedAtEpochMs)
                            .apply {
                                item.result?.let { put("result", it) }
                                item.error?.let { put("error", it) }
                                item.payload?.let { put("payload", it) }
                                put("attempts", item.attempts)
                                item.nextRetryAtEpochMs?.let { put("next_retry_at", it) }
                                put("manual_retry_allowed", item.manualRetryAllowed)
                            },
                    )
                }
            },
        )
        .put(
            "alerts",
            JSONArray().apply {
                alerts.forEach { item ->
                    put(
                        JSONObject()
                            .put("id", item.id)
                            .put("severity", item.severity)
                            .put("title", item.title)
                            .put("detail", item.detail)
                            .put("created_at", item.createdAtEpochMs)
                            .put("acknowledged", item.acknowledged),
                    )
                }
            },
        )

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val separator = encoded.indexOf('.')
        require(separator > 0 && separator < encoded.lastIndex) { "Invalid protected ledger envelope." }
        val iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP)
        val ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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
        val lock = Any()
        const val PREFERENCES_NAME = "gpos_protected_ledger"
        const val KEY_LEDGER_BLOB = "ledger_blob"
        const val KEY_ALIAS = "gpos_protected_ledger_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_PENDING_TASKS = 50
        const val MAX_RECEIPTS = 60
        const val MAX_ALERTS = 40
    }
}

private fun parseLedger(json: JSONObject): LocalLedger = LocalLedger(
    pendingTasks = json.optJSONArray("pending_tasks").mapObjects { item ->
        PendingTaskMutation(
            id = item.optString("id"),
            taskId = item.optString("task_id"),
            title = item.optString("title"),
            stagedAtEpochMs = item.optLong("staged_at"),
            syncAfterEpochMs = item.optLong("sync_after"),
            attempts = item.optInt("attempts", 0),
            taskListId = item.optString("task_list_id", "@default"),
            owner = item.optString("owner"),
        )
    }.filter { it.id.isNotBlank() && it.taskId.isNotBlank() },
    receipts = json.optJSONArray("receipts").mapObjects { item ->
        LocalReceipt(
            id = item.optString("id"),
            kind = item.optString("kind"),
            summary = item.optString("summary"),
            state = runCatching { LocalReceiptState.valueOf(item.optString("state")) }
                .getOrDefault(LocalReceiptState.FAILED),
            createdAtEpochMs = item.optLong("created_at"),
            updatedAtEpochMs = item.optLong("updated_at"),
            result = item.optString("result").trim().takeIf(String::isNotBlank),
            error = item.optString("error").trim().takeIf(String::isNotBlank),
            payload = item.optString("payload").trim().takeIf(String::isNotBlank),
            attempts = item.optInt("attempts", 0),
            nextRetryAtEpochMs = item.optLong("next_retry_at").takeIf { it > 0 },
            manualRetryAllowed = item.optBoolean("manual_retry_allowed", false),
        )
    }.filter { it.id.isNotBlank() },
    alerts = json.optJSONArray("alerts").mapObjects { item ->
        LocalAlert(
            id = item.optString("id"),
            severity = item.optString("severity", "warning"),
            title = item.optString("title"),
            detail = item.optString("detail"),
            createdAtEpochMs = item.optLong("created_at"),
            acknowledged = item.optBoolean("acknowledged", false),
        )
    }.filter { it.id.isNotBlank() },
)

private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(transform(it)) }
        }
    }
}
