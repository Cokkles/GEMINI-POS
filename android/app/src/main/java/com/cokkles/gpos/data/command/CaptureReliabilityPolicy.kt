package com.cokkles.gpos.data.command

import com.cokkles.gpos.data.remote.AegisBackendException

object CaptureInputNormalizer {
    fun normalize(kind: CaptureKind, input: String): String {
        val trimmed = input.trim()
        if (kind != CaptureKind.CALORIES) return trimmed
        val parts = trimmed.split(Regex("[,;\\n]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        return if (parts.size > 1) parts.joinToString("\n") { "• $it" } else trimmed
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
