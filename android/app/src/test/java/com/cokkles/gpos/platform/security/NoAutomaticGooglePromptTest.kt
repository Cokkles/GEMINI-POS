package com.cokkles.gpos.platform.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAutomaticGooglePromptTest {
    @Test
    fun `credential manager can only be reached through explicit sign in controls`() {
        val sourceRoot = File("src/main/java")
        val callers = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("requestIdToken(") }
            .map { it.name }
            .toSet()

        assertTrue(callers.contains("MainActivity.kt"))
        assertTrue(callers.contains("GposActivity.kt"))
        assertTrue(callers.contains("DailyDriverActivity.kt"))
        assertTrue(callers.contains("ParityActivity.kt"))
        assertTrue(callers.contains("GoogleSignInCoordinator.kt"))
        assertFalse(callers.any { it.contains("Worker") || it.contains("ViewModel") })
        assertFalse(
            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .any { it.readText().contains("requestAuthorizedIdToken") },
        )
    }
}
