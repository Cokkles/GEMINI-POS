package com.cokkles.gpos.data.interaction

import com.cokkles.gpos.BuildConfig
import com.cokkles.gpos.data.workspace.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cokkles.gpos.data.remote.AegisBackendException
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

data class InteractionCapabilities(
    val followupsV1: Boolean = false,
    val taskActionV1: Boolean = false,
    val aiQueryV1: Boolean = false,
    val calendarAiV2: Boolean = false,
    val taskWorkspaceV1: Boolean = false,
    val taskCrudV1: Boolean = false,
    val taskListsV1: Boolean = false,
    val taskHistoryV1: Boolean = false,
)

data class AegisFollowup(
    val id: String,
    val title: String,
    val summary: String,
    val priority: String,
    val status: String,
    val promotedTaskId: String?,
)

data class FollowupsResult(
    val items: List<AegisFollowup>,
    val activeCount: Int,
)

data class CreatedTask(
    val id: String,
    val title: String,
    val status: String,
    val due: String?,
)

data class TaskCreationResult(
    val task: CreatedTask,
    val localId: String?,
)

data class AiChatMessage(
    val role: String,
    val text: String,
)

data class AiQueryResult(
    val answer: String,
    val mode: String,
    val requestId: String?,
    val mutationPerformed: Boolean,
)

data class CalendarCandidate(
    val id: String?,
    val title: String,
    val start: String?,
    val end: String?,
)

data class CalendarInteractionResult(
    val operation: String,
    val answer: String,
    val mutationPerformed: Boolean,
    val confirmationRequired: Boolean,
    val confirmationToken: String?,
    val expiresAt: String?,
    val previewSummary: String?,
    val candidates: List<CalendarCandidate>,
)

data class CalendarConfirmationResult(
    val operation: String,
    val answer: String,
    val mutationPerformed: Boolean,
)

/**
 * Typed client for the additive AEGIS 2.6.5 interaction contracts used by Android 0.6.
 *
 * There is deliberately no public generic execute/action method. Every call is named after an
 * advertised shared contract, and mutation-capable methods are invoked only from explicit user
 * actions in the foreground UI. This client never retries a non-idempotent mutation automatically.
 */
class AegisInteractionClient(
    private val backendUrl: String = BuildConfig.GPOS_BACKEND_URL,
) {
    private val transport: InteractionTransport = Retrofit.Builder()
        .baseUrl("https://script.google.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(false)
                .build(),
        )
        .build()
        .create(InteractionTransport::class.java)

    suspend fun readCapabilities(idToken: String): InteractionCapabilities {
        val json = postAuthenticated(idToken, InteractionPayloads.capabilities())
        val root = json.optJSONObject("data") ?: json
        val ux = root.optJSONObject("ux_contracts") ?: JSONObject()
        val features = root.optJSONObject("features") ?: JSONObject()
        return InteractionCapabilities(
            followupsV1 = ux.optBoolean("followups_v1", false),
            taskActionV1 = ux.optBoolean("task_action_v1", false),
            aiQueryV1 = features.optBoolean("ai_query_v1", false),
            calendarAiV2 = features.optBoolean("calendar_ai_v2", false),
            taskWorkspaceV1 = ux.optBoolean("task_workspace_v1"),
            taskCrudV1 = ux.optBoolean("task_crud_v1"),
            taskListsV1 = ux.optBoolean("task_lists_v1"),
            taskHistoryV1 = ux.optBoolean("tasks_history_v1"),
        )
    }

    suspend fun readTaskWorkspace(token: String): TaskWorkspace = parseTaskWorkspace(readTaskWorkspaceJson(token))

    suspend fun readTaskWorkspaceJson(token: String): JSONObject =
        postAuthenticated(token, JSONObject().put("action", "get_task_workspace"))

    suspend fun readWorkspaceHistory(token: String, days: Int): List<WorkspaceTask> {
        require(days == 7 || days == 30)
        val result = postAuthenticated(token, JSONObject().put("action", "get_task_history").put("days", days))
        require(result.optString("contract") == "AEGIS_TASK_HISTORY_V1") { "Task history contract unavailable." }
        return parseWorkspaceTasks(result.optJSONArray("items"))
    }

    suspend fun saveWorkspaceTask(token: String, listId: String, taskId: String?, title: String, notes: String, due: String, localId: String) {
        val result = postAuthenticated(token, WorkspacePayloads.save(listId, taskId, title, notes, due, localId))
        require(result.optJSONObject("task")?.optString("id")?.isNotBlank() == true) { "Task save was not confirmed. Refresh before retrying." }
    }

    suspend fun deleteWorkspaceTask(token: String, task: WorkspaceTask) {
        val result = postAuthenticated(token, WorkspacePayloads.task("delete_task", task.listId, task.id))
        require(result.optString("operation") == "DELETE") { "Task deletion was not confirmed." }
    }

    suspend fun restoreWorkspaceTask(token: String, task: WorkspaceTask) {
        val result = postAuthenticated(token, WorkspacePayloads.task("restore_task", task.listId, task.id))
        require(result.optString("operation") == "RESTORE") { "Task restoration was not confirmed." }
    }

    suspend fun saveWorkspaceList(token: String, title: String, listId: String?) {
        val result = postAuthenticated(token, WorkspacePayloads.list(title, listId))
        require(result.optJSONObject("task_list")?.optString("id")?.isNotBlank() == true) { "List save was not confirmed. Refresh before retrying." }
    }

    suspend fun readFollowups(idToken: String): FollowupsResult {
        val json = postAuthenticated(idToken, InteractionPayloads.followups())
        val root = json.optJSONObject("data") ?: json
        val array = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                add(
                    AegisFollowup(
                        id = id,
                        title = title,
                        summary = item.optString("summary").trim(),
                        priority = item.optString("priority", "MEDIUM").trim().ifBlank { "MEDIUM" },
                        status = item.optString("status", "ACTIVE").trim().ifBlank { "ACTIVE" },
                        promotedTaskId = item.optString("promoted_task_id").trim().takeIf(String::isNotBlank),
                    ),
                )
            }
        }
        return FollowupsResult(
            items = items,
            activeCount = root.optInt("active_count", items.count { !it.status.equals("RESOLVED", true) && !it.status.equals("DISMISSED", true) }),
        )
    }

    suspend fun createTask(
        idToken: String,
        title: String,
        notes: String,
        localId: String,
    ): TaskCreationResult {
        val json = postAuthenticated(idToken, InteractionPayloads.createTask(title, notes, localId))
        val task = json.optJSONObject("task")
            ?: throw AegisBackendException("TASK_RESULT_MISSING", "AEGIS returned no created Task payload.")
        val id = task.optString("id").trim()
        val taskTitle = task.optString("title").trim()
        if (id.isBlank() || taskTitle.isBlank()) {
            throw AegisBackendException("TASK_RESULT_INVALID", "AEGIS returned an incomplete created Task payload.")
        }
        return TaskCreationResult(
            task = CreatedTask(
                id = id,
                title = taskTitle,
                status = task.optString("status", "needsAction"),
                due = task.optString("due").trim().takeIf(String::isNotBlank),
            ),
            localId = json.optString("local_id").trim().takeIf(String::isNotBlank),
        )
    }

    suspend fun resolveFollowup(idToken: String, followupId: String, title: String) {
        postAuthenticated(idToken, InteractionPayloads.followupLifecycle("resolve_followup", followupId, title))
    }

    suspend fun dismissFollowup(idToken: String, followupId: String, title: String) {
        postAuthenticated(idToken, InteractionPayloads.followupLifecycle("dismiss_followup", followupId, title))
    }

    suspend fun promoteFollowupToTask(
        idToken: String,
        followupId: String,
        title: String,
        notes: String,
    ): TaskCreationResult {
        val json = postAuthenticated(idToken, InteractionPayloads.promoteFollowup(followupId, title, notes))
        val task = json.optJSONObject("task")
            ?: throw AegisBackendException("TASK_RESULT_MISSING", "AEGIS returned no promoted Task payload.")
        return TaskCreationResult(
            task = CreatedTask(
                id = task.optString("id").trim().takeIf(String::isNotBlank)
                    ?: throw AegisBackendException("TASK_RESULT_INVALID", "Promoted Task ID is missing."),
                title = task.optString("title").trim().takeIf(String::isNotBlank)
                    ?: throw AegisBackendException("TASK_RESULT_INVALID", "Promoted Task title is missing."),
                status = task.optString("status", "needsAction"),
                due = task.optString("due").trim().takeIf(String::isNotBlank),
            ),
            localId = null,
        )
    }

    suspend fun askAegis(
        idToken: String,
        question: String,
        mode: String,
        history: List<AiChatMessage>,
    ): AiQueryResult {
        val json = postAuthenticated(idToken, InteractionPayloads.aiQuery(question, mode, history))
        val answer = json.optString("answer").trim().takeIf(String::isNotBlank)
            ?: throw AegisBackendException("AI_QUERY_EMPTY", "AEGIS returned no answer.")
        return AiQueryResult(
            answer = answer,
            mode = json.optString("mode", mode).trim().ifBlank { mode },
            requestId = json.optString("request_id").trim().takeIf(String::isNotBlank),
            mutationPerformed = json.optBoolean("mutation_performed", false),
        )
    }

    suspend fun prepareCalendar(idToken: String, question: String): CalendarInteractionResult {
        val json = postAuthenticated(idToken, InteractionPayloads.calendarAi(question))
        return parseCalendarInteraction(json)
    }

    suspend fun confirmCalendar(idToken: String, confirmationToken: String): CalendarConfirmationResult {
        val json = postAuthenticated(idToken, InteractionPayloads.calendarConfirm(confirmationToken))
        return CalendarConfirmationResult(
            operation = json.optString("operation", "UNKNOWN"),
            answer = json.optString("answer").trim().ifBlank { "Calendar change confirmed and applied." },
            mutationPerformed = json.optBoolean("mutation_performed", false),
        )
    }

    private fun parseCalendarInteraction(json: JSONObject): CalendarInteractionResult {
        val candidatesJson = json.optJSONArray("candidates") ?: JSONArray()
        val candidates = buildList {
            for (index in 0 until candidatesJson.length()) {
                val item = candidatesJson.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                add(
                    CalendarCandidate(
                        id = item.optString("id").trim().takeIf(String::isNotBlank),
                        title = title,
                        start = item.optString("start").trim().takeIf(String::isNotBlank),
                        end = item.optString("end").trim().takeIf(String::isNotBlank),
                    ),
                )
            }
        }
        return CalendarInteractionResult(
            operation = json.optString("operation", "READ").trim().ifBlank { "READ" },
            answer = json.optString("answer").trim().ifBlank { "AEGIS Calendar completed the request." },
            mutationPerformed = json.optBoolean("mutation_performed", false),
            confirmationRequired = json.optBoolean("confirmation_required", false),
            confirmationToken = json.optString("confirmation_token").trim().takeIf(String::isNotBlank),
            expiresAt = json.optString("expires_at").trim().takeIf(String::isNotBlank),
            previewSummary = summarizeProposal(json.optJSONObject("proposal")),
            candidates = candidates,
        )
    }

    private fun summarizeProposal(proposal: JSONObject?): String? {
        if (proposal == null) return null
        val operation = proposal.optString("operation").trim().ifBlank { "CHANGE" }
        val event = proposal.optJSONObject("event")
        val target = proposal.optJSONObject("target")
        val changes = proposal.optJSONObject("changes")
        val subject = event ?: target
        val title = subject?.optString("title")?.trim().orEmpty()
        val start = when {
            changes?.optString("start")?.isNotBlank() == true -> changes.optString("start")
            subject?.optString("start")?.isNotBlank() == true -> subject.optString("start")
            else -> ""
        }
        val end = when {
            changes?.optString("end")?.isNotBlank() == true -> changes.optString("end")
            subject?.optString("end")?.isNotBlank() == true -> subject.optString("end")
            else -> ""
        }
        return buildString {
            append(operation.lowercase().replaceFirstChar { it.uppercase() })
            if (title.isNotBlank()) append(" • ").append(title)
            if (start.isNotBlank()) append(" • ").append(start)
            if (end.isNotBlank()) append(" → ").append(end)
        }
    }

    private suspend fun postAuthenticated(idToken: String, payload: JSONObject): JSONObject {
        require(idToken.isNotBlank()) { "Authentication token missing." }
        payload.put("auth_token", idToken)
        val response = transport.post(backendUrl, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        val json = withContext(Dispatchers.IO) { parseResponse(response) }
        ensureSuccess(json)
        return json
    }

    private fun parseResponse(response: Response<ResponseBody>): JSONObject {
        val raw = response.body()?.use { body ->
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw AegisBackendException("RESPONSE_TOO_LARGE", "AEGIS interaction response exceeded the Android safety limit.")
            }
            body.string()
        } ?: response.errorBody()?.use { it.string() }
        ?: throw AegisBackendException("EMPTY_RESPONSE", "AEGIS returned an empty interaction response.")

        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw AegisBackendException("RESPONSE_TOO_LARGE", "AEGIS interaction response exceeded the Android safety limit.")
        }
        if (!response.isSuccessful) {
            throw AegisBackendException("HTTP_${response.code()}", "AEGIS interaction failed with HTTP ${response.code()}.")
        }
        return runCatching { JSONObject(raw) }
            .getOrElse { throw AegisBackendException("INVALID_JSON", "AEGIS returned invalid interaction JSON.") }
    }

    private fun ensureSuccess(json: JSONObject) {
        val code = json.optString("code")
        if (code == "AEGIS_AUTH_REQUIRED" || code == "AEGIS_AUTH_FAILED") {
            throw AegisBackendException(code, json.optString("error", "AEGIS authentication required."))
        }
        if (json.optString("status").equals("error", true) || json.has("error")) {
            throw AegisBackendException(code.ifBlank { "AEGIS_INTERACTION_FAILED" }, json.optString("error", "AEGIS interaction failed."))
        }
    }

    private interface InteractionTransport {
        @POST
        suspend fun post(@Url url: String, @Body body: RequestBody): Response<ResponseBody>
    }

    private companion object {
        val JSON_MEDIA_TYPE = "text/plain;charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 35L
        const val CALL_TIMEOUT_SECONDS = 40L
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    }
}

internal object InteractionPayloads {
    fun capabilities(): JSONObject = JSONObject().put("action", "get_capabilities")

    fun followups(): JSONObject = JSONObject().put("action", "get_followups")

    fun createTask(title: String, notes: String, localId: String): JSONObject {
        val cleanTitle = title.trim()
        val cleanNotes = notes.trim()
        require(cleanTitle.isNotBlank()) { "Task title is required." }
        require(cleanTitle.length <= 500) { "Task title is too long." }
        require(cleanNotes.length <= 4000) { "Task notes are too long." }
        require(localId.isNotBlank()) { "Task local ID is required." }
        return JSONObject()
            .put("action", "create_task")
            .put("title", cleanTitle)
            .put("notes", cleanNotes)
            .put("local_id", localId)
    }

    fun followupLifecycle(action: String, followupId: String, title: String): JSONObject {
        require(action == "resolve_followup" || action == "dismiss_followup") { "Unsupported follow-up action." }
        val id = followupId.trim()
        require(id.isNotBlank()) { "Follow-up ID is required." }
        return JSONObject()
            .put("action", action)
            .put("followup_id", id)
            .put("title", title.trim().take(500))
    }

    fun promoteFollowup(followupId: String, title: String, notes: String): JSONObject {
        val id = followupId.trim()
        val cleanTitle = title.trim()
        require(id.isNotBlank()) { "Follow-up ID is required." }
        require(cleanTitle.isNotBlank()) { "Task title is required." }
        return JSONObject()
            .put("action", "promote_followup_task")
            .put("followup_id", id)
            .put("title", cleanTitle.take(500))
            .put("notes", notes.trim().take(4000))
    }

    fun aiQuery(question: String, mode: String, history: List<AiChatMessage>): JSONObject {
        val cleanQuestion = question.trim()
        require(cleanQuestion.isNotBlank()) { "A question is required." }
        val normalizedMode = mode.trim().lowercase().takeIf { it in AI_MODES } ?: "general"
        val array = JSONArray()
        history.takeLast(8).forEach { item ->
            val role = item.role.lowercase().takeIf { it == "user" || it == "assistant" } ?: "user"
            val text = item.text.trim().take(1800)
            if (text.isNotBlank()) array.put(JSONObject().put("role", role).put("text", text))
        }
        return JSONObject()
            .put("action", "ai_query")
            .put("question", cleanQuestion.take(4000))
            .put("mode", normalizedMode)
            .put("history", array)
    }

    fun calendarAi(question: String): JSONObject {
        val value = question.trim()
        require(value.isNotBlank()) { "A Calendar request is required." }
        return JSONObject()
            .put("action", "calendar_ai")
            .put("question", value.take(4000))
    }

    fun calendarConfirm(token: String): JSONObject {
        val value = token.trim()
        require(value.isNotBlank()) { "Calendar confirmation token is required." }
        return JSONObject()
            .put("action", "calendar_confirm")
            .put("confirmation_token", value)
    }

    private val AI_MODES = setOf("general", "career", "finance", "logistics", "system")
}
