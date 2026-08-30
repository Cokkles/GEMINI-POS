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
    fun explicitMutationShellNavigatesWithSignedOutWritesDisabled() {
        composeRule.onNodeWithText("Quote of the day").assertExists()
        composeRule.onNodeWithText("GPOS Android 0.3.0-dev").assertExists()

        composeRule.onNodeWithText("Briefing").performClick()
        composeRule.onNodeWithText("Refresh briefing").assertExists()

        composeRule.onNodeWithText("Calendar").performClick()
        composeRule.onNodeWithText("Add an event").assertExists()
        composeRule.onNodeWithText("Resolve and preview").assertExists()
        composeRule.onNodeWithText("Sign in online to enable Calendar creation.").assertExists()

        composeRule.onNodeWithText("Tasks").performClick()
        composeRule.onNodeWithText("Selections are local staging only", substring = true).assertExists()
        composeRule.onNodeWithText("Task writes disabled").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("More GPOS").assertExists()

        composeRule.onNodeWithText("Notifications").performClick()
        composeRule.onNodeWithText("Server notifications").assertExists()
        composeRule.onNodeWithText("Opening or deep-linking never acknowledges", substring = true).assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Finances").performClick()
        composeRule.onNodeWithText("Recent activity").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Insights").performClick()
        composeRule.onNodeWithText("News & insights").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Follow-ups").performClick()
        composeRule.onNodeWithText("Integration intentionally gated").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("System").performClick()
        composeRule.onNodeWithText("Android OAuth registration").assertExists()
        composeRule.onNodeWithText("Background refresh").assertExists()
        composeRule.onNodeWithText("Appearance").assertExists()
    }
}
