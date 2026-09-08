package com.cokkles.gpos.data.command

import com.cokkles.gpos.data.remote.AegisBackendException

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
        return backend.contract == CONTRACT &&
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
}
