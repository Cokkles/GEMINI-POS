package com.cokkles.gpos.data.remote

import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class ServerNotification(
    val id: String,
    val title: String,
    val message: String,
    val severity: NotificationSeverity,
    val type: String,
    val detail: String?,
    val createdAtEpochMs: Long?,
    val acknowledged: Boolean,
)

enum class NotificationSeverity {
    INFO,
    WARNING,
    CRITICAL,
    UNKNOWN,
}

data class NotificationsSnapshot(
    val notifications: List<ServerNotification>,
) {
    val active: List<ServerNotification> get() = notifications.filterNot { it.acknowledged }
    val activeCriticalCount: Int get() = active.count { it.severity == NotificationSeverity.CRITICAL }
}

object NotificationsPayloadMapper {
    fun map(json: JSONObject): NotificationsSnapshot {
        if (json.optString("status").equals("error", ignoreCase = true)) {
            throw IllegalArgumentException(
                json.optString("error").ifBlank { "Notifications response reported an error." },
            )
        }
        val array = json.optJSONArray("notifications")
            ?: throw IllegalArgumentException("Notifications response did not contain notifications.")
        return NotificationsSnapshot(mapNotifications(array))
    }

    private fun mapNotifications(array: JSONArray): List<ServerNotification> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("title").trim().ifBlank { "GPOS notification" }
            val message = item.optString("message").trim()
            val type = item.optString("type").trim().ifBlank { "AEGIS" }
            val created = item.optString("createdAt").trim()
            add(
                ServerNotification(
                    id = item.optString("id").trim().takeIf { it.isNotBlank() }
                        ?: stableId(type, title, message, created),
                    title = title,
                    message = message,
                    severity = when (item.optString("severity").lowercase()) {
                        "info" -> NotificationSeverity.INFO
                        "warning", "warn" -> NotificationSeverity.WARNING
                        "critical", "error" -> NotificationSeverity.CRITICAL
                        else -> NotificationSeverity.UNKNOWN
                    },
                    type = type,
                    detail = item.optString("detail").trim().takeIf { it.isNotBlank() },
                    createdAtEpochMs = parseInstant(created),
                    acknowledged = item.optBoolean("acknowledged", false),
                ),
            )
        }
    }

    private fun parseInstant(value: String?): Long? =
        value?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun stableId(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("\u001f").toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}

data class NotificationsRuntimeState(
    val snapshot: NotificationsSnapshot,
    val source: RuntimeDataSource,
    val fetchedAtEpochMs: Long,
    val error: String? = null,
)
