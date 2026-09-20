package com.cokkles.gpos

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

/** Exercises the real launcher used by the shipped APK, in a fresh signed-out emulator. */
class DailyNavigationRegressionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ParityActivity>()

    private fun tab(route: String, title: String) {
        composeRule.onNodeWithTag("nav_$route").performClick()
        composeRule.onNodeWithTag("current_destination").assertTextEquals(title)
        composeRule.onNodeWithTag("nav_$route").assertIsSelected()
    }

    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test fun homeShortcutThenBottomTabsAlwaysOpenNamedPage() {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Tasks"))
        composeRule.onNodeWithTag("home_tasks").performClick()
        composeRule.onNodeWithTag("current_destination").assertTextEquals("Tasks")
        repeat(2) {
            tab("calendar", "Calendar")
            tab("tasks", "Tasks")
            tab("capture", "Notes")
            tab("home", "Home")
        }
    }

    @Test fun moreDoesNotResurrectSearchOrReceiptsAfterSwitchingTabs() {
        tab("more", "More")
        scrollTo("Search")
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithTag("current_destination").assertTextEquals("Search")
        tab("calendar", "Calendar")
        tab("more", "More")
        tab("capture", "Notes")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("capture_receipts_button"))
        composeRule.onNodeWithTag("capture_receipts_button").performClick()
        composeRule.onNodeWithTag("current_destination").assertTextEquals("Alerts & Receipts")
        tab("tasks", "Tasks")
        tab("more", "More")
    }

    @Test fun diagnosticsHasExplicitRawHorizonControlAndHonestEmptyState() {
        tab("more", "More")
        scrollTo("Settings")
        composeRule.onNodeWithText("Settings").performClick()
        scrollTo("Show raw HORIZON text")
        composeRule.onNodeWithText("Show raw HORIZON text").performClick()
        scrollTo("No canonical HORIZON text is cached yet.")
        composeRule.onNodeWithText("No canonical HORIZON text is cached yet.").assertExists()
    }
    @Test fun calendarAndRunningNotesHomeShortcutsOpenTheirScreens() {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("home_calendar"))
        composeRule.onNodeWithTag("home_calendar").performClick()
        composeRule.onNodeWithTag("current_destination").assertTextEquals("Calendar")
        tab("home", "Home")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("home_running_notes"))
        composeRule.onNodeWithTag("home_running_notes").performClick()
        composeRule.onNodeWithTag("current_destination").assertTextEquals("Notes")
        composeRule.onNodeWithTag("running_notes_editor").assertExists()
        tab("tasks", "Tasks")
        composeRule.onNodeWithTag("task_list_picker").assertExists()
    }

    @Test fun headlinerControlsAreReachableAndDealsDefaultOff() {
        tab("more", "More")
        scrollTo("News & Insights")
        composeRule.onNodeWithText("News & Insights").performClick()
        composeRule.onNodeWithTag("headliner_settings").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("headline_deals"))
        composeRule.onNodeWithTag("headline_deals").assertIsOff()
    }

}
