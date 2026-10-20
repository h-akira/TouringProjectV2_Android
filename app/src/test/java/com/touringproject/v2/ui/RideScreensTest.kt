package com.touringproject.v2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideScreensTest {
  @get:Rule val rule = createComposeRule()

  @Test
  fun without_a_ride_the_card_offers_to_start_one() {
    var starts = 0
    rule.setContent { RideCard(null, { starts++ }, {}, {}, {}) }
    rule.onNodeWithText("記録しなくても質問できます（日ごとの記録に残ります）").assertExists()
    rule.onNodeWithTag("startRide").performClick()
    assertEquals(1, starts)
  }

  @Test
  fun a_recording_ride_shows_its_time_and_distance_and_can_pause_or_end() {
    val events = mutableListOf<String>()
    rule.setContent { RideCard(RideCardUi("箱根", false, "1:26", "52.8 km", "4"), {}, { events += "pause" }, { events += "resume" }, { events += "end" }) }
    rule.onNodeWithText("● 走行を記録中").assertExists()
    rule.onNodeWithText("1:26").assertExists()
    rule.onNodeWithText("52.8 km").assertExists()
    rule.onNodeWithTag("rideQuestions").onChildAt(0).assertTextEquals("4")
    rule.onNodeWithText("質問").assertExists()
    rule.onNodeWithText("⏸ 一時停止").performClick()
    rule.onNodeWithTag("endRide").performClick()
    assertEquals(listOf("pause", "end"), events)
  }

  @Test
  fun the_quiet_texts_follow_the_theme_so_they_stay_readable_in_dark_mode() {
    val dark = darkColorScheme()
    rule.setContent { MaterialTheme(colorScheme = dark) { RideCard(RideCardUi("箱根", true, "1:26", "52.8 km", "4"), {}, {}, {}, {}) } }
    for (text in listOf("箱根", "⏸ 一時停止中", "走行時間")) {
      val layouts = mutableListOf<TextLayoutResult>()
      rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertEquals(text, dark.onSurfaceVariant, layouts.single().layoutInput.style.color)
    }
  }

  @Test
  fun a_paused_ride_says_its_questions_are_not_recorded_and_can_resume() {
    var resumes = 0
    rule.setContent { RideCard(RideCardUi("箱根", true, "1:26", "52.8 km", "4"), {}, {}, { resumes++ }, {}) }
    rule.onNodeWithText("一時停止中の質問は走行の記録に入りません（日ごとには残ります）").assertExists()
    rule.onNodeWithText("▶ 再開").performClick()
    assertEquals(1, resumes)
  }

  @Test
  fun the_start_sheet_needs_a_name() {
    var started: String? = null
    rule.setContent { StartRideSheet(locationAllowed = true, onStart = { started = it }, onDismiss = {}) }
    rule.onNodeWithTag("startGo").assertIsNotEnabled()
    rule.onNodeWithTag("rideName").performTextInput("  ")
    rule.onNodeWithTag("startGo").assertIsNotEnabled()
    rule.onNodeWithTag("rideName").performTextInput("箱根 ")
    rule.onNodeWithTag("startGo").assertIsEnabled().performClick()
    assertEquals("箱根", started)
  }

  @Test
  fun a_pasted_name_is_cut_at_40_characters() {
    var started: String? = null
    rule.setContent { StartRideSheet(locationAllowed = true, onStart = { started = it }, onDismiss = {}) }
    rule.onNodeWithTag("rideName").performTextInput("あ".repeat(45))
    rule.onNodeWithTag("startGo").performClick()
    assertEquals("あ".repeat(40), started)
  }

  @Test
  fun a_pasted_name_is_never_cut_inside_an_emoji() {
    var started: String? = null
    rule.setContent { StartRideSheet(locationAllowed = true, onStart = { started = it }, onDismiss = {}) }
    rule.onNodeWithTag("rideName").performTextInput("あ".repeat(39) + "😀😀")
    rule.onNodeWithTag("startGo").performClick()
    assertEquals("あ".repeat(39) + "😀", started)
  }

  @Test
  fun the_start_sheet_says_why_without_the_location_permission() {
    rule.setContent { StartRideSheet(locationAllowed = false, onStart = {}, onDismiss = {}) }
    rule.onNodeWithTag("noLocation").assertExists()
    rule.onNodeWithTag("rideName").performTextInput("箱根")
    rule.onNodeWithTag("startGo").assertIsNotEnabled()
  }

  @Test
  fun the_end_sheet_shows_the_summary_and_keeps_the_name_from_being_emptied() {
    var ended: String? = null
    var dismissed = 0
    rule.setContent {
      EndRideSheet(EndSummaryUi("1:30", "62.4 km", "4", "08:58〜10:46 · 一時停止 1回（18分）は走行時間に含めません"), "箱根", { ended = it }, { dismissed++ })
    }
    rule.onNodeWithText("08:58〜10:46 · 一時停止 1回（18分）は走行時間に含めません").assertExists()
    rule.onNodeWithTag("endQuestions").onChildAt(0).assertTextEquals("4")
    rule.onNodeWithTag("rideName").performTextClearance()
    rule.onNodeWithTag("endGo").assertIsNotEnabled()
    rule.onNodeWithTag("rideName").performTextInput("箱根から御殿場へ")
    rule.onNodeWithTag("endGo").performClick()
    assertEquals("箱根から御殿場へ", ended)
    rule.onNodeWithText("走行を続ける").performClick()
    assertEquals(1, dismissed)
  }
}
