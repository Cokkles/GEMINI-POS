package com.cokkles.gpos.data.remote

import com.cokkles.gpos.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import java.time.Instant
import java.util.concurrent.TimeUnit

class AegisBackendClient(
    private val backendUrl: String = BuildConfig.GPOS_BACKEND_URL,
) {
    private val transport: AegisTransport = Retrofit.Builder()
        .baseUrl("https://script.google.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build(),
        )
        .build()
        .create(AegisTransport::class.java)

    suspend fun getAuthConfig(): AuthConfig {
        val response = transport.get("$backendUrl?action=auth_config")
        val json = parseResponse(response)
        ensureSuccess(json, allowUnauthenticated = true)
        return AuthConfig(
            provider = json.optString("provider", "unknown"),
            configured = json.optBoolean("configured", false),
            allowlistConfigured = json.optBoolean("allowlist_configured", false),
            enforcementRequired = json.optBoolean("enforcement_required", true),
            authVersion = json.optString("auth_version", "unknown"),
            backendVersion = json.optString("backend_version", "unknown"),
            clientId = json.optString("client_id").takeIf { it.isNotBlank() },
        )
    }

    suspend fun authenticate(idToken: String): AuthenticatedSession =
        parseAuthenticatedSession(postJson("auth_login", idToken))

    suspend fun validateSession(idToken: String): AuthenticatedSession =
        parseAuthenticatedSession(postJson("auth_session", idToken))

    suspend fun logout(idToken: String) {
        runCatching { postJson("auth_logout", idToken) }
    }

    suspend fun readHealth(idToken: String): JSONObject = protectedRead("get_health", idToken)

    suspend fun readDashboard(idToken: String): JSONObject = protectedRead("get_dashboard", idToken)

    suspend fun readCapabilities(idToken: String): JSONObject = protectedRead("get_capabilities", idToken)

    suspend fun readLatestHorizon(idToken: String): JSONObject = protectedRead("get_latest_horizon", idToken)

    suspend fun readCalendarRange(
        idToken: String,
        startDate: String,
        endDate: String,
    ): JSONObject = protectedRead(
        action = "get_calendar_range",
        idToken = idToken,
        additionalPayload = JSONObject()
            .put("start_date", startDate)
            .put("end_date", endDate),
    )

    suspend fun readIntelligence(
        idToken: String,
        force: Boolean = false,
    ): JSONObject = protectedRead(
        action = "get_intelligence",
        idToken = idToken,
        additionalPayload = JSONObject().put("force", force),
    )

    suspend fun readNutritionSummary(
        idToken: String,
        days: Int,
    ): JSONObject = protectedRead(
        action = "get_nutrition_summary",
        idToken = idToken,
        additionalPayload = JSONObject().put("days", days.coerceIn(1, 30)),
    )

    suspend fun readTaskHistory(
        idToken: String,
        days: Int,
    ): JSONObject = protectedRead(
        action = "get_task_history",
        idToken = idToken,
        additionalPayload = JSONObject().put("days", days.coerceIn(1, 30)),
    )

    suspend fun readRecentFinance(idToken: String, hours: Int = 72): JSONObject {
        val boundedHours = hours.coerceIn(1, 168)
        return protectedRead(
            action = "get_recent_finance",
            idToken = idToken,
            additionalPayload = JSONObject().put("hours", boundedHours),
        )
    }

    suspend fun readNotifications(idToken: String): JSONObject =
        protectedRead("get_notifications", idToken)

    private suspend fun protectedRead(
        action: String,
        idToken: String,
        additionalPayload: JSONObject? = null,
    ): JSONObject {
        require(action in READ_ONLY_ACTIONS) { "Unsupported protected read action: $action" }
        return postJson(action, idToken, additionalPayload)
    }

    private suspend fun postJson(
        action: String,
        idToken: String,
        additionalPayload: JSONObject? = null,
    ): JSONObject {
        require(idToken.isNotBlank()) { "Authentication token missing." }
        val payload = JSONObject()
            .put("action", action)
            .put("auth_token", idToken)
        if (additionalPayload != null) {
            additionalPayload.keys().forEach { key -> payload.put(key, additionalPayload.get(key)) }
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val json = parseResponse(transport.post(backendUrl, body))
        ensureSuccess(json, allowUnauthenticated = action == "auth_login" || action == "auth_session")
        return json
    }

    private fun parseAuthenticatedSession(json: JSONObject): AuthenticatedSession {
        if (!json.optBoolean("authenticated", false)) {
            throw AegisBackendException(
                code = json.optString("code", "AEGIS_AUTH_FAILED"),
                message = json.optString("error", "AEGIS authentication was rejected."),
            )
        }
        val userJson = json.optJSONObject("user")
            ?: throw AegisBackendException("AEGIS_AUTH_FAILED", "Authenticated response did not contain a user.")
        val sessionJson = json.optJSONObject("session")
        val expiresAt = sessionJson
            ?.optString("expires_at")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        return AuthenticatedSession(
            user = AuthenticatedUser(
                email = userJson.optString("email", "Authorized"),
                name = userJson.optString("name").takeIf { it.isNotBlank() },
                pictureUrl = userJson.optString("picture").takeIf { it.isNotBlank() },
            ),
            expiresAtEpochMs = expiresAt,
        )
    }

    private fun parseResponse(response: Response<ResponseBody>): JSONObject {
        val raw = response.body()?.use { body ->
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw AegisBackendException("RESPONSE_TOO_LARGE", "AEGIS response exceeded the Android safety limit.")
            }
            body.string()
        } ?: response.errorBody()?.use { it.string() }
        ?: throw AegisBackendException("EMPTY_RESPONSE", "AEGIS returned an empty response.")

        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw AegisBackendException("RESPONSE_TOO_LARGE", "AEGIS response exceeded the Android safety limit.")
        }
        if (!response.isSuccessful) {
            throw AegisBackendException("HTTP_${response.code()}", "AEGIS request failed with HTTP ${response.code()}.")
        }
        return runCatching { JSONObject(raw) }
            .getOrElse { throw AegisBackendException("INVALID_JSON", "AEGIS returned invalid JSON.") }
    }

    private fun ensureSuccess(json: JSONObject, allowUnauthenticated: Boolean) {
        val code = json.optString("code")
        val status = json.optString("status")
        if (code == "AEGIS_AUTH_REQUIRED" || code == "AEGIS_AUTH_FAILED") {
            throw AegisBackendException(code, json.optString("error", "AEGIS authentication required."))
        }
        if (!allowUnauthenticated && status.equals("error", ignoreCase = true)) {
            throw AegisBackendException(code.ifBlank { "AEGIS_ERROR" }, json.optString("error", "AEGIS request failed."))
        }
    }

    private interface AegisTransport {
        @GET
        suspend fun get(@Url url: String): Response<ResponseBody>

        @POST
        suspend fun post(@Url url: String, @Body body: RequestBody): Response<ResponseBody>
    }

    private companion object {
        val JSON_MEDIA_TYPE = "text/plain;charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 20L
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L

        val READ_ONLY_ACTIONS = setOf(
            "get_dashboard",
            "get_health",
            "get_capabilities",
            "get_latest_horizon",
            "get_calendar_range",
            "get_intelligence",
            "get_nutrition_summary",
            "get_task_history",
            "get_recent_finance",
            "get_notifications",
        )
    }
}

class AegisBackendException(
    val code: String,
    override val message: String,
) : Exception(message)
