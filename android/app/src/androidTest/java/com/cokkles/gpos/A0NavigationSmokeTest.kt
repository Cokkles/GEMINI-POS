package com.cokkles.gpos

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AndroidNavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<DailyDriverActivity>()

    @Test
    fun dailyDriverNavigatesWithSignedOutWritesDisabled() {
        composeRule.onNodeWithText("Daily inspiration").assertExists()
        composeRule.onNodeWithText("Today").assertExists()

        composeRule.onNodeWithText("Briefing").performClick()
        composeRule.onNodeWithText("Refresh briefing").assertExists()

        composeRule.onNodeWithText("Calendar").performClick()
        composeRule.onNodeWithText("Quick add").assertExists()
        composeRule.onNodeWithText("Resolve & preview").assertExists()
        composeRule.onNodeWithText("Live authenticated session required for creation.").assertExists()

        composeRule.onNodeWithText("Tasks").performClick()
        composeRule.onNodeWithText("Active tasks", substring = true).assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("More GPOS").assertExists()

        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithText("Search synced GPOS").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Notifications").performClick()
        composeRule.onNodeWithText("Attention center", substring = true).assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Finances").performClick()
        composeRule.onNodeWithText("Money snapshot").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Insights").performClick()
        composeRule.onNodeWithText("News & strategic insights").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("System").performClick()
        composeRule.onNodeWithText("Sync health").assertExists()
        composeRule.onNodeWithText("Appearance").assertExists()
        composeRule.onNodeWithText("Show developer diagnostics").assertExists()
    }
}
