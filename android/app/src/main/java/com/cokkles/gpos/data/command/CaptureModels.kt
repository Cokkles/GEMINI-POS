package com.cokkles.gpos.data.command

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
        .put("message", "${kind.prefix} $mode$body")
        .put("submission_id", submissionId)
}
