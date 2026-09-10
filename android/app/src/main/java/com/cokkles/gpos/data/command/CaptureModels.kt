package com.cokkles.gpos.data.command

import com.cokkles.gpos.BuildConfig
import org.json.JSONObject

enum class CaptureKind(
    val wireName: String,
    val prefix: String,
    val displayName: String,
) {
    NOTE("note", "/note", "Note"),
    JOURNAL("journal", "/journal", "Journal"),
    VENT("vent", "/vent", "Vent"),
    REFLECT("reflect", "/journal", "Reflect"),
    ASSESS("assess", "/journal", "Assess"),
    CALORIES("calories", "/calories", "Meal / Calories"),
    RECEIPT("receipt", "/receipts", "Receipt / Finance"),
}

data class CaptureSubmissionResult(
    val message: String,
    val durationMs: Long,
    val backendStatus: String? = null,
    val captureStatus: String? = null,
    val contract: String? = null,
    val totalCalories: Double? = null,
    val confidence: String? = null,
    val lookupDepth: Int? = null,
    val deduplicated: Boolean = false,
    val terminal: Boolean = true,
    val serverManaged: Boolean = false,
    val retryAfterMs: Long? = null,
    val diagnosticCode: String? = null,
)

internal fun capturePayload(
    kind: CaptureKind,
    text: String,
    submissionId: String,
): JSONObject {
    val body = text.trim()
    require(body.isNotBlank()) { "Capture text is required." }
    require(body.length <= 8000) { "Capture text exceeds the 8,000 character safety limit." }
    val mode = when (kind) {
        CaptureKind.REFLECT -> "[REFLECT] "
        CaptureKind.ASSESS -> "[ASSESS] "
        else -> ""
    }
    return JSONObject()
        .apply {
            if (kind == CaptureKind.CALORIES) {
                put("action", "capture_nutrition")
                put("capture_id", submissionId)
                put("nutrition_contract", "AEGIS_NUTRITION_CAPTURE_V2")
                put("source_policy", "OFFICIAL_USDA_OPEN_FOOD_FACTS_COMPONENT_ESTIMATE_V1")
            }
        }
        .put("message", "${kind.prefix} $mode$body")
        .put("submission_id", submissionId)
        .put("client_id", "GPOS_ANDROID")
        .put("client_version", BuildConfig.VERSION_NAME)
}

internal fun enqueueNutritionPayload(
    text: String,
    submissionId: String,
): JSONObject = capturePayload(CaptureKind.CALORIES, text, submissionId)
    .put("action", "enqueue_nutrition_capture")
    .put("nutrition_contract", "AEGIS_NUTRITION_CAPTURE_ASYNC_V1")

internal fun nutritionCaptureStatusPayload(submissionId: String): JSONObject =
    JSONObject()
        .put("action", "get_nutrition_capture_status")
        .put("capture_id", submissionId)
        .put("submission_id", submissionId)
        .put("client_id", "GPOS_ANDROID")
        .put("client_version", BuildConfig.VERSION_NAME)

internal fun retryNutritionCapturePayload(submissionId: String): JSONObject =
    nutritionCaptureStatusPayload(submissionId)
        .put("action", "retry_nutrition_capture")
