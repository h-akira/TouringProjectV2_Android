package com.touringproject.v2.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.MainActivity
import com.touringproject.v2.TouringApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUiTest {
  @get:Rule val rule = createAndroidComposeRule<MainActivity>()

  @Before
  fun ready() {
    (rule.activity.application as TouringApp).graph.permissionsAnswered()
    rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("tab-HOME") }
  }

  private fun back() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

  @Test
  fun back_goes_back_within_settings_then_to_home() {
    rule.onNodeWithTag("tab-SETTINGS").performClick()
    rule.onNodeWithText("回答の音量").performClick()
    rule.onNodeWithText("回答の音声を上げる量").assertExists()
    back()
    rule.onNodeWithText("走行の記録").assertExists()
    back()
    rule.onNodeWithTag("tapToTalk").assertExists()
  }

  @Test
  fun the_records_tab_says_its_list_comes_later_and_home_keeps_its_place() {
    rule.onNodeWithTag("tab-RECORDS").performClick()
    rule.onNodeWithText("走行と日ごとの記録の一覧は、次の版でここに出ます。").assertExists()
    rule.onNodeWithTag("tab-HOME").performClick()
    rule.onNodeWithTag("tapToTalk").assertExists()
  }

  @Test
  fun the_user_chip_opens_the_user_name_in_the_settings_tab() {
    val graph = (rule.activity.application as TouringApp).graph
    rule.onNodeWithText(graph.settings.current().userName).performClick()
    rule.onNodeWithTag("tab-SETTINGS").assertExists()
    back()
    rule.onNodeWithText("走行の記録").assertExists()
  }

  @Test
  fun the_start_sheet_stays_open_when_the_activity_is_recreated() {
    rule.onNodeWithTag("startRide").performClick()
    rule.onNodeWithTag("startGo").assertExists()
    rule.activityRule.scenario.recreate()
    rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("startGo") }
  }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagExists(tag: String) =
  onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
