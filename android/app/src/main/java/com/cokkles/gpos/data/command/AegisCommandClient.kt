package com.cokkles.gpos.data.command

import com.cokkles.gpos.BuildConfig
import com.cokkles.gpos.data.remote.AegisBackendException
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.net.SocketTimeoutException
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Explicit foreground command boundary.
 *
 * This client intentionally has no generic public execute(action) method and is not used by
 * CanonicalSyncWorker. Every method maps to a production-proven mutation envelope and must be
 * invoked only after the corresponding foreground user-intent policy is satisfied by the caller.
 * Non-idempotent commands are never retried automatically here.
 */
class AegisCommandClient(
    private val backendUrl: String = BuildConfig.GPOS_BACKEND_URL,
) {
    private val transport: CommandTransport = Retrofit.Builder()
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
        .create(CommandTransport::class.java)

    suspend fun completeTasks(
        idToken: String,
        taskIds: Collection<String>,
        taskListId: String = "@default",
    ): TaskCompletionResult {
        val json = postAuthenticated(idToken, AegisCommandPayloads.completeTasks(taskIds, taskListId))
        return TaskCompletionResult(
            message = json.optString("result").takeIf { it.isNotBlank() }
                ?: "Selected Google Tasks were submitted for completion.",
        )
    }

    suspend fun resolveCalendarEvent(
        idToken: String,
        text: String,
    ): CalendarResolutionResult {
        val json = postAuthenticated(idToken, AegisCommandPayloads.resolveCalendar(text))
        val event = json.optJSONObject("event")
            ?: throw AegisBackendException(
                "INVALID_CALENDAR_PROPOSAL",
                "AEGIS returned no structured calendar event proposal.",
            )
        return CalendarResolutionResult(ResolvedCalendarEvent.fromJson(event))
    }

    suspend fun createCalendarEvent(
        idToken: String,
        event: ResolvedCalendarEvent,
    ): CalendarCreationResult {
        val json = postAuthenticated(idToken, AegisCommandPayloads.createCalendar(event))
        val returnedJson = json.optJSONObject("event")
        val returnedEvent = returnedJson
            ?.takeIf {
                it.optString("title").isNotBlank() &&
                    it.optString("start").isNotBlank() &&
                    it.optString("end").isNotBlank()
            }
            ?.let(ResolvedCalendarEvent::fromJson)
        return CalendarCreationResult(
            event = returnedEvent,
            message = json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("result").takeIf { it.isNotBlank() }
                ?: "Calendar event created.",
        )
    }

    suspend fun submitRunningNotes(token: String, document: com.cokkles.gpos.data.workspace.RunningNotesDocument): String {
        val json = postAuthenticated(token, com.cokkles.gpos.data.workspace.runningNotesPayload(document))
        val result = json.optString("result")
        check(result.startsWith("✅ Logged entry to Notes & Ideas Log:")) { "Journal submission was not confirmed. Keep the local draft and check the journal." }
        return "Saved to Notes & Ideas Log"
    }

    suspend fun submitCapture(
        idToken: String,
        kind: CaptureKind,
        text: String,
        submissionId: String,
    ): CaptureSubmissionResult {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val json = postAuthenticated(idToken, capturePayload(kind, text, submissionId))
            val confirmation = CaptureCompletionParser.parse(json, kind)
            return CaptureSubmissionResult(
                message = confirmation.message,
                durationMs = SystemClock.elapsedRealtime() - startedAt,
                backendStatus = confirmation.backendStatus,
                contract = confirmation.contract,
                totalCalories = confirmation.totalCalories,
            )
        } catch (error: SocketTimeoutException) {
            throw AegisBackendException(
                code = "CAPTURE_TIMEOUT",
                message = "AEGIS did not respond within ${CALL_TIMEOUT_SECONDS} seconds. The write state is unknown; check Nutrition before manually retrying.",
                retryable = false,
                writeState = "UNKNOWN",
            )
        } catch (error: IOException) {
            throw AegisBackendException(
                code = "CAPTURE_NETWORK_FAILURE",
                message = "The AEGIS connection ended before a confirmation was received. The write state is unknown; check Nutrition before manually retrying.",
                retryable = false,
                writeState = "UNKNOWN",
            )
        }
    }

    suspend fun acknowledgeNotification(
        idToken: String,
        notificationId: String,
    ): NotificationAcknowledgementResult {
        val normalizedId = notificationId.trim()
        val json = postAuthenticated(
            idToken,
            AegisCommandPayloads.acknowledgeNotification(normalizedId),
        )
        return AegisCommandPayloads.parseNotificationAcknowledgement(json, normalizedId)
    }

    private suspend fun postAuthenticated(
        idToken: String,
        payload: JSONObject,
    ): JSONObject {
        require(idToken.isNotBlank()) { "Authentication token missing." }
        payload.put("auth_token", idToken)
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val response = transport.post(backendUrl, body)
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { parseResponse(response) }
        ensureSuccess(json)
        return json
    }

    private fun ensureSuccess(json: JSONObject) {
        val code = json.optString("code")
        if (code == "AEGIS_AUTH_REQUIRED" || code == "AEGIS_AUTH_FAILED") {
            throw AegisBackendException(code, json.optString("error", "AEGIS authentication required."))
        }
        if (json.optString("status").equals("error", ignoreCase = true) || json.has("error")) {
            throw AegisBackendException(
                code.ifBlank { "AEGIS_COMMAND_FAILED" },
                json.optString("error", "AEGIS command failed."),
                contract = json.optString("contract").takeIf(String::isNotBlank),
                retryable = json.takeIf { it.has("retryable") }?.optBoolean("retryable"),
                writeState = json.optString("write_state").takeIf(String::isNotBlank),
            )
        }
    }

    private fun parseResponse(response: Response<ResponseBody>): JSONObject {
        val raw = response.body()?.use { body ->
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw AegisBackendException(
                    "RESPONSE_TOO_LARGE",
                    "AEGIS command response exceeded the Android safety limit.",
                )
            }
            body.string()
        } ?: response.errorBody()?.use { it.string() }
        ?: throw AegisBackendException("EMPTY_RESPONSE", "AEGIS returned an empty command response.")

        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw AegisBackendException(
                "RESPONSE_TOO_LARGE",
                "AEGIS command response exceeded the Android safety limit.",
            )
        }
        if (!response.isSuccessful) {
            throw AegisBackendException(
                "HTTP_${response.code()}",
                "AEGIS command failed with HTTP ${response.code()}.",
            )
        }
        return runCatching { JSONObject(raw) }
            .getOrElse {
                throw AegisBackendException("INVALID_JSON", "AEGIS returned invalid command JSON.")
            }
    }

    private interface CommandTransport {
        @POST
        suspend fun post(@Url url: String, @Body body: RequestBody): Response<ResponseBody>
    }

    private companion object {
        val JSON_MEDIA_TYPE = "text/plain;charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 10L
        // Apps Script + Gemini + Sheets can legitimately exceed the short read window used by
        // ordinary AEGIS commands. The request runs in WorkManager, so this does not block UI.
        const val READ_TIMEOUT_SECONDS = 180L
        const val CALL_TIMEOUT_SECONDS = 185L
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    }
}
