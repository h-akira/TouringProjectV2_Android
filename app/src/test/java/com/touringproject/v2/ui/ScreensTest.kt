package com.touringproject.v2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.Phase
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
  @get:Rule val rule = createComposeRule()

  @Test
  fun tap_to_talk_starts_a_screen_question() {
    var taps = 0
    rule.setContent { HomeScreen(HomeUi("rider01", null, "新しい会話です"), { taps++ }, {}, {}, {}, {}) }
    rule.onNodeWithTag("tapToTalk").performClick()
    assertEquals(1, taps)
  }

  @Test
  fun the_banner_leads_to_its_setting() {
    var opened: Banner? = null
    rule.setContent { HomeScreen(HomeUi("rider01", Banner.NO_KEY, "新しい会話です"), {}, {}, {}, { opened = it }, {}) }
    rule.onNodeWithText(Banner.NO_KEY.text).performClick()
    assertEquals(Banner.NO_KEY, opened)
  }

  @Test
  fun a_new_conversation_is_confirmed_first() {
    var resets = 0
    rule.setContent { HomeScreen(HomeUi("rider01", null, "新しい会話です"), {}, {}, {}, {}, { resets++ }) }
    rule.onNodeWithTag("newConversation").performClick()
    assertEquals(0, resets)
    rule.onNodeWithText("やめる").performClick()
    rule.onNodeWithTag("newConversation").performClick()
    rule.onNodeWithText("新しい会話にする").performClick()
    assertEquals(1, resets)
  }
}

@RunWith(AndroidJUnit4::class)
class AskOverlayTest {
  @get:Rule val rule = createComposeRule()

  @Test
  fun nothing_is_shown_while_idle() {
    rule.setContent { AskOverlay(FlowState(), { 0L }, {}, {}) }
    rule.onNodeWithTag("overlay").assertDoesNotExist()
  }

  @Test
  fun while_recording_the_big_area_sends_and_the_button_cancels() {
    var sent = 0
    var cancelled = 0
    rule.setContent { AskOverlay(FlowState(phase = Phase.RECORDING), { 0L }, { sent++ }, { cancelled++ }) }
    rule.onNodeWithText("話してください").assertIsDisplayed()
    rule.onNodeWithTag("overlayMain").performClick()
    rule.onNodeWithText("送らずにやめる").performClick()
    assertEquals(1, sent)
    assertEquals(1, cancelled)
  }

  @Test
  fun the_phone_mic_warning_shows_while_recording() {
    rule.setContent { AskOverlay(FlowState(phase = Phase.RECORDING, phoneMic = true), { 0L }, {}, {}) }
    rule.onNodeWithText("インカムのマイクに繋がっていません（本体のマイクで録音中）").assertIsDisplayed()
  }

  @Test
  fun while_speaking_the_answer_is_shown_and_tapping_stops_it() {
    var cancelled = 0
    rule.setContent { AskOverlay(FlowState(phase = Phase.SPEAKING, answer = "愛鷹山です。"), { 0L }, {}, { cancelled++ }) }
    rule.onNodeWithText("愛鷹山です。").assertIsDisplayed()
    rule.onNodeWithTag("overlayMain").performClick()
    assertEquals(1, cancelled)
  }
}

@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {
  @get:Rule val rule = createComposeRule()
  private val maps = LaunchableApp("com.google.android.apps.maps", "マップ")

  @Test
  fun an_invalid_user_name_is_refused_and_a_valid_one_is_confirmed() {
    val changed = mutableListOf<String>()
    rule.setContent { UserScreen("rider01", { changed += it }) }
    rule.onNodeWithTag("userInput").performTextInput("No!")
    rule.onNodeWithTag("changeUser").performClick()
    rule.onNodeWithTag("userError").assertIsDisplayed()
    rule.onNodeWithTag("userInput").performTextClearance()
    rule.onNodeWithTag("userInput").performTextInput("rider02")
    rule.onNodeWithTag("changeUser").performClick()
    rule.onNodeWithText("変える").performClick()
    assertEquals(listOf("rider02"), changed)
  }

  @Test
  fun choosing_a_return_app_also_remembers_it() {
    var updated: Settings? = null
    rule.setContent { ReturnAppScreen(Settings("rider01"), listOf(maps), { updated = it }) }
    rule.onNodeWithTag("app-${maps.packageName}").performClick()
    assertEquals(maps, updated!!.returnApp)
    assertEquals(listOf(maps), updated!!.recentApps)
  }

  @Test
  fun a_saved_key_is_shown_masked() {
    rule.setContent { ConnectionScreen("abcdef1234567890", urlMissing = false, check = {}, save = {}) }
    rule.onNodeWithTag("currentKey").assertIsDisplayed()
    rule.onNodeWithText("••••••••7890").assertIsDisplayed()
  }

  @Test
  fun a_new_key_is_checked_then_saved() {
    val saved = mutableListOf<String?>()
    rule.setContent { ConnectionScreen(null, urlMissing = false, check = {}, save = { saved += it }) }
    rule.onNodeWithTag("keyInput").performTextInput(" new-key ")
    rule.onNodeWithTag("saveKey").performClick()
    rule.waitUntil { saved.isNotEmpty() }
    assertEquals(listOf<String?>("new-key"), saved)
    rule.onNodeWithText("保存しました").assertIsDisplayed()
  }
}
