package com.cokkles.gpos

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AndroidNavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<GposActivity>()

    @Test
    fun canonicalReadOnlyShellNavigatesWithoutMutationDependency() {
        composeRule.onNodeWithText("Quote of the day").assertExists()
        composeRule.onNodeWithText("GPOS Android 0.2.0-dev").assertExists()

        composeRule.onNodeWithText("Briefing").performClick()
        composeRule.onNodeWithText("Refresh briefing").assertExists()

        composeRule.onNodeWithText("Calendar").performClick()
        composeRule.onNodeWithText("Refresh calendar").assertExists()

        composeRule.onNodeWithText("Tasks").performClick()
        composeRule.onNodeWithText("Read-only in 0.2").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("More GPOS").assertExists()

        composeRule.onNodeWithText("Follow-ups").performClick()
        composeRule.onNodeWithText("Canonical integration pending").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Finances").performClick()
        composeRule.onNodeWithText("SENTINEL-FIN remains canonical authority.", substring = true).assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Ask AEGIS").performClick()
        composeRule.onNodeWithText("Conversation surface reserved").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("System").performClick()
        composeRule.onNodeWithText("Appearance").assertExists()
        composeRule.onNodeWithText("Backend & authentication").assertExists()
    }
}
