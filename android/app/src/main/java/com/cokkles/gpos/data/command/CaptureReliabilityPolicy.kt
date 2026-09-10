package com.cokkles.gpos.data.command

import com.cokkles.gpos.data.remote.AegisBackendException
import org.json.JSONObject

object CaptureInputNormalizer {
    fun normalize(kind: CaptureKind, input: String): String {
        // Preserve the user's wording for every capture kind. In particular, do not translate
        // comma-separated meals into a Unicode bullet list: Apps Script 2.8.0 forwards this text
        // to Gemini, and changing its format can turn an otherwise parseable meal into the
        // backend's zero-macro fallback row.
        return input.trim()
    }
}

object CaptureReliabilityPolicy {
    const val CONTRACT = "AEGIS_CAPTURE_RELIABILITY_V1"
    const val NUTRITION_CONTRACT = "AEGIS_NUTRITION_CAPTURE_V2"
    const val MAX_ATTEMPTS = 3

    fun isGeminiDependent(kind: CaptureKind): Boolean =
        kind == CaptureKind.CALORIES || kind == CaptureKind.RECEIPT

    fun retryDelayMs(attemptsCompleted: Int): Long? = when (attemptsCompleted) {
        1 -> 60_000L
        2 -> 120_000L
        else -> null
    }

    fun isCertifiedSafeCapacityFailure(error: Throwable): Boolean {
        val backend = error as? AegisBackendException ?: return false
        return backend.contract in setOf(CONTRACT, NUTRITION_CONTRACT) &&
            backend.retryable == true &&
            backend.writeState == "NOT_STARTED" &&
            backend.code in setOf(
                "GEMINI_CAPACITY",
                "GEMINI_HIGH_VOLUME",
                "RESOURCE_EXHAUSTED",
                "GEMINI_RATE_LIMITED",
            )
    }

    fun isUnconfirmedResult(message: String): Boolean {
        val normalized = message.lowercase()
        return "macros pending" in normalized ||
            "gemini_api_key" in normalized ||
            ("high volume" in normalized && "try again" in normalized)
    }

    fun diagnosticCode(error: Throwable): String = when (error) {
        is AegisBackendException -> error.code.ifBlank { "AEGIS_COMMAND_FAILED" }
        else -> error::class.java.simpleName.ifBlank { "UNKNOWN_FAILURE" }
    }
}

data class CaptureConfirmation(
    val message: String,
    val backendStatus: String?,
    val captureStatus: String?,
    val contract: String?,
    val totalCalories: Double?,
    val confidence: String?,
    val lookupDepth: Int?,
    val deduplicated: Boolean,
    val terminal: Boolean,
    val serverManaged: Boolean,
    val retryAfterMs: Long?,
    val diagnosticCode: String?,
)

object CaptureCompletionParser {
    fun parse(json: JSONObject, kind: CaptureKind): CaptureConfirmation {
        val totalCalories = json.takeIf { it.has("totalCalories") && !it.isNull("totalCalories") }
            ?.optDouble("totalCalories")
            ?.takeIf(Double::isFinite)
        val message = sequenceOf("result", "answer", "message")
            .map { json.optString(it).trim() }
            .firstOrNull(String::isNotBlank)
            ?: if (kind == CaptureKind.CALORIES && totalCalories != null) {
                "Meal accepted by AEGIS. Daily total: ${totalCalories.toInt()} kcal."
            } else {
                throw AegisBackendException(
                    "CAPTURE_RESULT_MISSING",
                    "AEGIS accepted the connection but returned no meaningful capture result.",
                )
            }
        return CaptureConfirmation(
            message = message,
            backendStatus = json.optString("status").trim().takeIf(String::isNotBlank),
            captureStatus = json.optString("capture_status").trim().takeIf(String::isNotBlank),
            contract = json.optString("contract").trim().takeIf(String::isNotBlank),
            totalCalories = totalCalories,
            confidence = json.optString("confidence").trim().takeIf(String::isNotBlank),
            lookupDepth = json.optInt("lookup_depth").takeIf { it in 1..5 },
            deduplicated = json.optBoolean("deduplicated", false),
            terminal = json.optBoolean(
                "terminal",
                json.optString("capture_status").equals("CONFIRMED", ignoreCase = true),
            ),
            serverManaged = json.optBoolean("server_managed", false),
            retryAfterMs = json.optLong("retry_after_ms").takeIf { it > 0L },
            diagnosticCode = sequenceOf("diagnostic_code", "last_error_code", "code")
                .map { json.optString(it).trim() }
                .firstOrNull(String::isNotBlank),
        )
    }
}
