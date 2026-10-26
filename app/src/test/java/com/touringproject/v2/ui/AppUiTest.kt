package com.touringproject.v2.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.MainActivity
import com.touringproject.v2.TouringApp
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

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
    rule.onNodeWithText("回答の音量").performScrollTo().performClick()
    rule.onNodeWithText("回答の音声を上げる量").assertExists()
    back()
    rule.onNodeWithText("走行の記録").assertExists()
    back()
    rule.onNodeWithTag("tapToTalk").assertExists()
  }

  // Slice 4 spec §7.1: SC-14 opens from the settings, and with nothing kept all is saved.
  @Test
  fun the_data_screen_opens_from_the_settings() {
    rule.onNodeWithTag("tab-SETTINGS").performClick()
    rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("保存済み")).fetchSemanticsNodes().isNotEmpty() }
    rule.onNodeWithText("☁️ データの保存").performClick()
    rule.onNodeWithTag("saveState").assertExists()
    rule.onNodeWithText("クラウドから読み直す").assertExists()
  }

  // Slice 5 spec §6: SC-11 opens from the settings, saves each change at once, and puts both values back with a toast.
  @Test
  fun the_recording_settings_save_at_once_and_go_back_to_their_defaults() {
    val graph = (rule.activity.application as TouringApp).graph
    rule.onNodeWithTag("tab-SETTINGS").performClick()
    rule.onNodeWithText("上限 20秒 · 合図 1.5秒").performScrollTo().performClick()
    rule.onNodeWithTag("limitUp").performClick()
    rule.onNodeWithTag("cue-0").performClick()
    rule.waitUntil(5_000) { graph.settings.current().let { it.recordLimitSeconds == 25 && it.startCueMs == 0 } }
    rule.onNodeWithTag("recordingDefaults").performClick()
    rule.waitUntil(5_000) { graph.settings.current().let { it.recordLimitSeconds == 20 && it.startCueMs == 1_500 } }
    assertEquals("既定値に戻しました", ShadowToast.getTextOfLatestToast())
    back()
    rule.onNodeWithText("上限 20秒 · 合図 1.5秒").assertExists()
  }

  @Test
  fun the_records_tab_lists_the_kept_rides_and_home_keeps_its_place() {
    val graph = (rule.activity.application as TouringApp).graph
    rule.onNodeWithTag("tab-RECORDS").performClick()
    // The list is read off the main thread and shared by AppGraph (Android #49): it comes a moment later.
    rule.waitUntil(5_000) {
      rule.onAllNodes(androidx.compose.ui.test.hasText("まだ走行の記録がありません。ホームの「▶ 走行を開始」で記録します。")).fetchSemanticsNodes().isNotEmpty()
    }
    val ride = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS).end(5_000, EndReason.MANUAL, 5_000)
    runBlocking { graph.rideStore.save(ride) }
    rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("ride-r1") }
    rule.onNodeWithText("箱根").assertExists()
    rule.onNodeWithTag("tab-HOME").performClick()
    rule.onNodeWithTag("tapToTalk").assertExists()
  }

  // Android #36: from deep in Settings, the user chip opens the user name above Settings' first screen.
  @Test
  fun the_user_chip_opens_the_user_name_above_the_first_settings_screen() {
    val graph = (rule.activity.application as TouringApp).graph
    rule.onNodeWithTag("tab-SETTINGS").performClick()
    rule.onNodeWithText("回答の音量").performClick()
    rule.onNodeWithTag("tab-HOME").performClick()
    rule.onNodeWithText(graph.settings.current().userName).performClick()
    rule.onNodeWithTag("userInput").assertExists()
    back()
    rule.onNodeWithText("走行の記録").assertExists()
  }

  // Android #34
  @Test
  fun a_name_being_typed_survives_a_trip_to_another_tab() {
    rule.onNodeWithTag("tab-SETTINGS").performClick()
    rule.onNodeWithText("ユーザー名").performClick()
    rule.onNodeWithTag("userInput").performTextInput("rider02")
    rule.onNodeWithTag("tab-HOME").performClick()
    rule.onNodeWithTag("tab-SETTINGS").performClick()
    rule.onNodeWithTag("userInput").assertTextContains("rider02")
  }

  // Android #32: on Home's first screen the app takes no Back; the system moves the app to the back.
  @Test
  fun the_app_leaves_back_on_homes_first_screen_to_the_system() {
    rule.onNodeWithTag("tapToTalk").assertExists()
    assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
  }

  // Android #36: the ask screen covers the tabs. Without a recording ride the intercom press fails at once and says so
  // (slice 2 spec §4.7).
  @Test
  fun the_tabs_hide_while_an_ask_is_on_screen() {
    val graph = (rule.activity.application as TouringApp).graph
    rule.runOnUiThread { graph.controller.press() }
    rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("tab-HOME") }
  }

  // Slice 5 spec §5.1: while a ride records, 「⌨ 文字で質問」 opens the sheet, which keeps the text over a recreation.
  @Test
  fun the_text_sheet_opens_while_a_ride_records_and_keeps_its_text() {
    val graph = (rule.activity.application as TouringApp).graph
    rule.onNodeWithTag("textAsk").assertIsNotEnabled()
    runBlocking { graph.rides.start("箱根") }
    rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("textAsk").and(androidx.compose.ui.test.isEnabled())).fetchSemanticsNodes().isNotEmpty() }
    rule.onNodeWithTag("textAsk").performClick()
    rule.onNodeWithTag("textAskInput").performTextInput("右手の山は？")
    rule.activityRule.scenario.recreate()
    rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("textAskInput") }
    rule.onNodeWithTag("textAskInput").assertTextContains("右手の山は？")
    rule.onNodeWithText("やめる").performClick()
    rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("textAskInput") }
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
