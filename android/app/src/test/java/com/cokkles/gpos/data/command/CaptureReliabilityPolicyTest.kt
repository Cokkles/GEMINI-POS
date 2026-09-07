package com.cokkles.gpos.data.command

import com.cokkles.gpos.data.remote.AegisBackendException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReliabilityPolicyTest {
    @Test fun `comma separated calories become a structured list`() {
        assertEquals("• eggs\n• toast\n• coffee", CaptureInputNormalizer.normalize(CaptureKind.CALORIES, "eggs, toast, coffee"))
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
}
