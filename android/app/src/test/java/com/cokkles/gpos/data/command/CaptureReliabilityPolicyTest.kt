package com.cokkles.gpos.data.command

import com.cokkles.gpos.data.remote.AegisBackendException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CaptureReliabilityPolicyTest {
    @Test fun `comma separated calories preserve original meal format`() {
        assertEquals("eggs, toast, coffee", CaptureInputNormalizer.normalize(CaptureKind.CALORIES, "  eggs, toast, coffee  "))
    }

    @Test fun `calorie payload requests structured nutrition while retaining legacy message`() {
        val payload = capturePayload(CaptureKind.CALORIES, "eggs, toast", "capture-1234")
        assertEquals("capture_nutrition", payload.getString("action"))
        assertEquals("capture-1234", payload.getString("capture_id"))
        assertEquals("/calories eggs, toast", payload.getString("message"))
        assertEquals("GPOS_ANDROID", payload.getString("client_id"))
    }

    @Test fun `async nutrition requests retain stable capture id`() {
        val enqueue = enqueueNutritionPayload("eggs, toast", "capture-1234")
        val status = nutritionCaptureStatusPayload("capture-1234")
        val retry = retryNutritionCapturePayload("capture-1234")
        assertEquals("enqueue_nutrition_capture", enqueue.getString("action"))
        assertEquals("get_nutrition_capture_status", status.getString("action"))
        assertEquals("retry_nutrition_capture", retry.getString("action"))
        assertEquals("capture-1234", enqueue.getString("capture_id"))
        assertEquals("capture-1234", status.getString("capture_id"))
        assertEquals("capture-1234", retry.getString("capture_id"))
    }

    @Test fun `newline separated calories preserve original meal format`() {
        assertEquals("eggs\ntoast\ncoffee", CaptureInputNormalizer.normalize(CaptureKind.CALORIES, "\neggs\ntoast\ncoffee\n"))
    }

    @Test fun `single calorie item preserves original wording`() {
        assertEquals("Oikos Pro vanilla protein shake", CaptureInputNormalizer.normalize(CaptureKind.CALORIES, " Oikos Pro vanilla protein shake "))
    }

    @Test fun `ordinary notes preserve punctuation`() {
        assertEquals("one, two", CaptureInputNormalizer.normalize(CaptureKind.NOTE, " one, two "))
    }

    @Test fun `only certified not-started capacity failures retry`() {
        assertTrue(CaptureReliabilityPolicy.isCertifiedSafeCapacityFailure(
            AegisBackendException("GEMINI_HIGH_VOLUME", "busy", CaptureReliabilityPolicy.CONTRACT, true, "NOT_STARTED"),
        ))
        assertFalse(CaptureReliabilityPolicy.isCertifiedSafeCapacityFailure(
            AegisBackendException("GEMINI_HIGH_VOLUME", "busy"),
        ))
    }

    @Test fun `retry schedule stops after three attempts`() {
        assertEquals(60_000L, CaptureReliabilityPolicy.retryDelayMs(1))
        assertEquals(120_000L, CaptureReliabilityPolicy.retryDelayMs(2))
        assertEquals(null, CaptureReliabilityPolicy.retryDelayMs(3))
    }

    @Test fun `backend 2_8 calorie response is confirmed`() {
        val parsed = CaptureCompletionParser.parse(
            JSONObject().put("result", "✅ LOGGED NUTRITION VIA GEMINI AI").put("totalCalories", 340),
            CaptureKind.CALORIES,
        )
        assertEquals("✅ LOGGED NUTRITION VIA GEMINI AI", parsed.message)
        assertEquals(340.0, parsed.totalCalories)
    }

    @Test fun `structured nutrition metadata is retained`() {
        val parsed = CaptureCompletionParser.parse(
            JSONObject()
                .put("status", "success")
                .put("capture_status", "CONFIRMED")
                .put("contract", "AEGIS_NUTRITION_CAPTURE_V2")
                .put("result", "Logged burger")
                .put("confidence", "MEDIUM_LOW")
                .put("lookup_depth", 5)
                .put("deduplicated", true),
            CaptureKind.CALORIES,
        )
        assertEquals("CONFIRMED", parsed.captureStatus)
        assertEquals("MEDIUM_LOW", parsed.confidence)
        assertEquals(5, parsed.lookupDepth)
        assertTrue(parsed.deduplicated)
    }

    @Test fun `server managed pending nutrition is not terminal`() {
        val parsed = CaptureCompletionParser.parse(
            JSONObject()
                .put("status", "success")
                .put("capture_status", "RETRY_SCHEDULED")
                .put("result", "Capture saved; waiting for capacity")
                .put("terminal", false)
                .put("server_managed", true)
                .put("retry_after_ms", 120_000),
            CaptureKind.CALORIES,
        )
        assertFalse(parsed.terminal)
        assertTrue(parsed.serverManaged)
        assertEquals(120_000L, parsed.retryAfterMs)
    }

    @Test fun `nutrition contract capacity failure is safe to retry`() {
        assertTrue(
            CaptureReliabilityPolicy.isCertifiedSafeCapacityFailure(
                AegisBackendException(
                    "GEMINI_RATE_LIMITED",
                    "busy",
                    CaptureReliabilityPolicy.NUTRITION_CONTRACT,
                    true,
                    "NOT_STARTED",
                ),
            ),
        )
    }

    @Test fun `calorie total is a valid fallback confirmation`() {
        val parsed = CaptureCompletionParser.parse(JSONObject().put("totalCalories", 340), CaptureKind.CALORIES)
        assertTrue(parsed.message.contains("Daily total: 340 kcal"))
    }

    @Test fun `unconfirmed backend fallback remains unsafe`() {
        assertTrue(CaptureReliabilityPolicy.isUnconfirmedResult("Macros pending - verify GEMINI_API_KEY"))
    }
}
