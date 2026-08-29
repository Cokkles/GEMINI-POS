package com.cokkles.gpos

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class A0NavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryAndMoreDestinationsRenderWhileRuntimeIntegrationIsReadOnly() {
        composeRule.onNodeWithText("Android 0.1").assertExists()
        composeRule.onNodeWithText("0.1 Preview Data").assertExists()
        composeRule.onNodeWithText("Quote of the day").assertExists()

        composeRule.onNodeWithText("Briefing").performClick()
        composeRule.onNodeWithText("Daily Executive Briefing").assertExists()

        composeRule.onNodeWithText("Calendar").performClick()
        composeRule.onNodeWithText("Agenda").assertExists()

        composeRule.onNodeWithText("Tasks").performClick()
        composeRule.onNodeWithText("Validate Android checkpoint").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("More GPOS").assertExists()

        composeRule.onNodeWithText("Follow-ups").performClick()
        composeRule.onNodeWithText("Review next Android integration increment").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Finances").performClick()
        composeRule.onNodeWithText("Finance surface ready for canonical summary").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Ask AEGIS").performClick()
        composeRule.onNodeWithText("Conversation surface reserved").assertExists()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("System").performClick()
        composeRule.onNodeWithText("Appearance").assertExists()
        composeRule.onNodeWithText("Backend & authentication").assertExists()
    }
}
