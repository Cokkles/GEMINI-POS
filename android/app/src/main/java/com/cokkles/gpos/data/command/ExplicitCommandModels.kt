package com.cokkles.gpos.data.command

import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed models for the deliberately small GPOS Android 0.3 mutation surface.
 *
 * These models do not expose a generic action/command field. New mutation types must be added
 * explicitly and reviewed against the READ AUTOMATICALLY / MUTATE EXPLICITLY policy.
 */
data class ResolvedCalendarEvent(
    val title: String,
    val start: String,
    val end: String,
    val location: String? = null,
    val description: String? = null,
) {
    init {
        require(title.isNotBlank()) { "Calendar event title is required." }
        require(start.isNotBlank()) { "Calendar event start is required." }
        require(end.isNotBlank()) { "Calendar event end is required." }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("start", start)
        .put("end", end)
        .apply {
            location?.takeIf { it.isNotBlank() }?.let { put("location", it) }
            description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        }

    companion object {
        fun fromJson(json: JSONObject): ResolvedCalendarEvent = ResolvedCalendarEvent(
            title = json.requiredString("title"),
            start = json.requiredString("start"),
            end = json.requiredString("end"),
            location = json.optString("location").takeIf { it.isNotBlank() },
            description = json.optString("description").takeIf { it.isNotBlank() },
        )
    }
}

data class TaskCompletionResult(
    val message: String,
)

data class CalendarResolutionResult(
    val event: ResolvedCalendarEvent,
)

data class CalendarCreationResult(
    val event: ResolvedCalendarEvent?,
    val message: String?,
)

data class NotificationAcknowledgementResult(
    val notificationId: String,
    val acknowledged: Boolean,
)

enum class CommandProgress {
    IDLE,
    SUBMITTING,
    VERIFYING,
}

data class TaskCommandRuntimeState(
    val stagedCanonicalIds: Set<String> = emptySet(),
    val progress: CommandProgress = CommandProgress.IDLE,
    val verificationNotBeforeEpochMs: Long? = null,
    val lastMessage: String? = null,
    val error: String? = null,
) {
    val canApply: Boolean
        get() = stagedCanonicalIds.isNotEmpty() && progress == CommandProgress.IDLE
}

enum class CalendarCommandProgress {
    IDLE,
    RESOLVING,
    READY_TO_CONFIRM,
    CREATING,
}

data class CalendarCommandRuntimeState(
    val sourceText: String? = null,
    val proposal: ResolvedCalendarEvent? = null,
    val progress: CalendarCommandProgress = CalendarCommandProgress.IDLE,
    val lastMessage: String? = null,
    val error: String? = null,
) {
    val canCreate: Boolean
        get() = proposal != null && progress == CalendarCommandProgress.READY_TO_CONFIRM
}

data class NotificationCommandRuntimeState(
    val submittingIds: Set<String> = emptySet(),
    val lastAcknowledgedId: String? = null,
    val error: String? = null,
)

internal object AegisCommandPayloads {
    const val COMPLETE_TASKS = "mark_done"
    const val RESOLVE_CALENDAR = "resolve_calendar_event"
    const val CREATE_CALENDAR = "create_calendar_event"
    const val ACK_NOTIFICATION = "ack_notification"

    fun completeTasks(taskIds: Collection<String>, taskListId: String = "@default"): JSONObject {
        require(taskListId.isNotBlank())
        val ids = taskIds.map(String::trim).filter(String::isNotBlank).distinct()
        require(ids.isNotEmpty()) { "At least one task must be selected for completion." }
        return JSONObject()
            .put("action", COMPLETE_TASKS)
            .put("task_list_id", taskListId)
            .put("message", "mark_done:")
            .put("completedTasks", JSONArray(ids))
    }

    fun resolveCalendar(text: String): JSONObject {
        val value = text.trim()
        require(value.isNotBlank()) { "Calendar event text is required." }
        return JSONObject()
            .put("action", RESOLVE_CALENDAR)
            .put("text", value)
    }

    fun createCalendar(event: ResolvedCalendarEvent): JSONObject = JSONObject()
        .put("action", CREATE_CALENDAR)
        .put("event", event.toJson())

    fun acknowledgeNotification(notificationId: String): JSONObject {
        val id = notificationId.trim()
        require(id.isNotBlank()) { "Notification ID is required." }
        return JSONObject()
            .put("action", ACK_NOTIFICATION)
            .put("notificationId", id)
    }

    fun parseNotificationAcknowledgement(
        json: JSONObject,
        requestedNotificationId: String,
    ): NotificationAcknowledgementResult {
        val returnedId = json.optString("notificationId").trim()
        val idMatches = returnedId.isNotBlank() && returnedId == requestedNotificationId
        return NotificationAcknowledgementResult(
            notificationId = requestedNotificationId,
            acknowledged = json.optString("status").equals("success", ignoreCase = true) && idMatches,
        )
    }
}

internal fun JSONObject.requiredString(key: String): String =
    optString(key).trim().takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required field: $key")
