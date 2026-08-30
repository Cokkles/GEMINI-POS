package com.cokkles.gpos.data.remote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class DashboardEvent(
    val id: String,
    val title: String,
    val timeLabel: String,
    val note: String?,
    val day: DashboardDay,
)

enum class DashboardDay {
    TODAY,
    TOMORROW,
}

data class DashboardTask(
    /** Stable identity for Android rendering, synthesized only when necessary. */
    val id: String,
    /**
     * Backend-provided Google Task identity. Only this value may be used by mutation code.
     * Null means the task is display-only and completion must remain disabled.
     */
    val canonicalId: String? = null,
    val title: String,
    val timeLabel: String?,
)

data class DashboardSnapshot(
    val todayEvents: List<DashboardEvent>,
    val tomorrowEvents: List<DashboardEvent>,
    val tasks: List<DashboardTask>,
    val briefingPlainText: String?,
    val briefingUpdatedAtEpochMs: Long?,
    val horizonLastSuccessAtEpochMs: Long?,
    val horizonMode: String?,
    val backendReportedUpdatedAtEpochMs: Long?,
)

object DashboardPayloadMapper {
    fun map(json: JSONObject): DashboardSnapshot {
        if (json.optString("status").equals("error", ignoreCase = true)) {
            throw IllegalArgumentException(
                json.optString("error").ifBlank { "Dashboard response reported an error." },
            )
        }

        val calendar = json.optJSONObject("calendar")
        val briefing = json.optJSONObject("briefing")
        val metadata = json.optJSONObject("system_metadata")
        val horizonGeneration = metadata?.optJSONObject("horizon_generation")

        return DashboardSnapshot(
            todayEvents = mapEvents(calendar?.optJSONArray("today"), DashboardDay.TODAY),
            tomorrowEvents = mapEvents(calendar?.optJSONArray("tomorrow"), DashboardDay.TOMORROW),
            tasks = mapTasks(json.optJSONArray("tasks")),
            briefingPlainText = briefing
                ?.optString("plain_text")
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            briefingUpdatedAtEpochMs = parseInstant(
                briefing?.optString("last_updated"),
            ),
            horizonLastSuccessAtEpochMs = parseInstant(
                horizonGeneration?.optString("last_success"),
            ),
            horizonMode = horizonGeneration
                ?.optString("mode")
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            backendReportedUpdatedAtEpochMs = parseInstant(
                metadata?.optString("last_updated"),
            ),
        )
    }

    private fun mapEvents(array: JSONArray?, day: DashboardDay): List<DashboardEvent> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                val time = item.optString("time", "All day").trim().ifBlank { "All day" }
                val note = item.optString("note").trim().takeIf { it.isNotBlank() }
                add(
                    DashboardEvent(
                        id = item.optString("id").trim().takeIf { it.isNotBlank() }
                            ?: stableId("event", day.name, title, time, note.orEmpty()),
                        title = title,
                        timeLabel = time,
                        note = note,
                        day = day,
                    ),
                )
            }
        }
    }

    private fun mapTasks(array: JSONArray?): List<DashboardTask> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                val time = item.optString("time").trim().takeIf { it.isNotBlank() }
                val canonicalId = item.optString("id").trim().takeIf { it.isNotBlank() }
                add(
                    DashboardTask(
                        id = canonicalId ?: stableId("task", title, time.orEmpty()),
                        canonicalId = canonicalId,
                        title = title,
                        timeLabel = time,
                    ),
                )
            }
        }
    }

    private fun parseInstant(value: String?): Long? =
        value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun stableId(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
    }
}
