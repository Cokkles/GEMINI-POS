package com.cokkles.gpos

import androidx.compose.ui.test.assertIsSelected
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
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Tasks • 0"))
        composeRule.onNodeWithTag("home_tasks").performClick()
        composeRule.onNodeWithTag("current_destination").assertTextEquals("Tasks")
        repeat(2) {
            tab("calendar", "Calendar")
            tab("tasks", "Tasks")
            tab("capture", "Capture")
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
        tab("capture", "Capture")
        scrollTo("View confirmations & receipts • 0")
        composeRule.onNodeWithText("View confirmations & receipts • 0").performClick()
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
}
