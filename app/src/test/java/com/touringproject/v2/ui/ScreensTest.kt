package com.touringproject.v2.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.Phase
import com.touringproject.v2.ask.RecordingMeta
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.recordings.TRIAL_IDLE
import com.touringproject.v2.recordings.TrialPhase
import com.touringproject.v2.recordings.TrialState
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

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
  fun the_low_volume_band_closes_with_its_cross_and_leads_nowhere() {
    var opened: Banner? = null
    var dismissed = 0
    rule.setContent { HomeScreen(HomeUi("rider01", Banner.LOW_VOLUME, "新しい会話です"), {}, {}, {}, { opened = it }, {}, onDismissVolume = { dismissed++ }) }
    rule.onNodeWithText(Banner.LOW_VOLUME.text).performClick()
    rule.onNodeWithTag("bandClose").performClick()
    assertNull(opened)
    assertEquals(1, dismissed)
  }

  @Test
  fun the_media_volume_is_read_while_home_is_shown_and_a_withheld_reading_keeps_the_last() {
    // 4b #2, and the carried-over rule: null (media on the call route) must not clear a low reading.
    val readings = ArrayDeque(listOf<MediaVolume?>(MediaVolume(2, 15), null))
    rule.setContent {
      val volume = rememberMediaVolume(readable = true) { readings.removeFirstOrNull() }
      Text(if (volumeWarning(volume, null)) "low" else "ok")
    }
    rule.onNodeWithText("low").assertIsDisplayed()
    rule.mainClock.advanceTimeBy(VOLUME_CHECK_MS * 2)
    rule.onNodeWithText("low").assertIsDisplayed()
  }

  @Test
  fun no_reading_is_taken_while_an_ask_holds_the_route() {
    var reads = 0
    rule.setContent { rememberMediaVolume(readable = false) { reads++; MediaVolume(2, 15) } }
    rule.mainClock.advanceTimeBy(VOLUME_CHECK_MS * 2)
    assertEquals(0, reads)
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
  fun the_low_volume_band_shows_while_recording_until_closed() {
    var dismissed: Int? = null
    val state = FlowState(phase = Phase.RECORDING, mediaVolume = MediaVolume(2, 15))
    rule.setContent { AskOverlay(state, { 0L }, {}, {}, volumeDismissedAt = null, onDismissVolume = { dismissed = it }) }
    rule.onNodeWithText(Banner.LOW_VOLUME.text).assertIsDisplayed()
    rule.onNodeWithTag("bandClose").performClick()
    assertEquals(2, dismissed)
  }

  @Test
  fun a_band_closed_at_this_volume_stays_closed() {
    val state = FlowState(phase = Phase.RECORDING, mediaVolume = MediaVolume(2, 15))
    rule.setContent { AskOverlay(state, { 0L }, {}, {}, volumeDismissedAt = 2) }
    rule.onNodeWithTag("overlayVolume").assertDoesNotExist()
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
  fun the_settings_list_leads_to_the_volume_and_the_recordings() {
    val opened = mutableListOf<String>()
    rule.setContent { SettingsScreen(Settings("rider01", answerVolumeDb = 3), null, {}, {}, {}, { opened += "volume" }, { opened += "recordings" }) }
    rule.onNodeWithText("+3dB").performClick()
    rule.onNodeWithText("録音の確認").performClick()
    assertEquals(listOf("volume", "recordings"), opened)
  }

  @Test
  fun the_answer_volume_is_chosen_from_three_steps() {
    var updated: Settings? = null
    rule.setContent { VolumeScreen(Settings("rider01"), { updated = it }) }
    rule.onNodeWithTag("volume-3").performClick()
    assertEquals(3, updated!!.answerVolumeDb)
    rule.onNodeWithTag("volume-0").assertIsDisplayed()
    rule.onNodeWithTag("volume-6").assertIsDisplayed()
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

// Robolectric's default screen is small; the list is checked on one the size of the Pixel 8a.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
class RecordingsScreenTest {
  @get:Rule val rule = createComposeRule()
  private val intercom = InputDevice(InputDevice.Type.BLUETOOTH, "M1-S PRO")
  private val heard = RecordingMeta("rec-2.pcm", Instant.parse("2026-10-17T01:35:07Z").toEpochMilli(), 2_300, false, intercom, "富士山の高さは")
  private val notHeard = RecordingMeta("rec-1.pcm", Instant.parse("2026-10-17T01:30:00Z").toEpochMilli(), 800, true, null, "")
  private fun ui(trial: TrialState = TRIAL_IDLE, playing: String? = null, askBusy: Boolean = false) =
    RecordingsUi(trial, listOf(heard, notHeard), playing, askBusy, null)

  @Test
  fun kept_recordings_are_listed_with_what_was_heard() {
    // 4b #5
    rule.setContent { RecordingsScreen(ui(), {}, {}, {}, {}) }
    rule.onNodeWithText("10/17 10:35:07　2.3秒").assertIsDisplayed()
    rule.onNodeWithText("インカム（M1-S PRO）").assertIsDisplayed()
    rule.onNodeWithText("富士山の高さは").assertIsDisplayed()
    rule.onNodeWithText("本体のマイク").assertIsDisplayed()
    rule.onNodeWithText("聞き取れませんでした").assertIsDisplayed()
  }

  @Test
  fun nothing_kept_yet() {
    rule.setContent { RecordingsScreen(RecordingsUi(TRIAL_IDLE, emptyList(), null, false, null), {}, {}, {}, {}) }
    rule.onNodeWithText("まだありません").assertIsDisplayed()
  }

  @Test
  fun a_row_plays_its_recording_and_stops_it() {
    val played = mutableListOf<String>()
    var stopped = 0
    var playing by mutableStateOf<String?>(null)
    rule.setContent { RecordingsScreen(ui(playing = playing), {}, { played += it }, { stopped++ }, {}) }
    rule.onNodeWithTag("play-rec-2.pcm").performClick()
    playing = "rec-2.pcm"
    rule.onNodeWithTag("play-rec-1.pcm").assertIsNotEnabled()
    rule.onNodeWithTag("play-rec-2.pcm").performClick()
    assertEquals(listOf("rec-2.pcm"), played)
    assertEquals(1, stopped)
  }

  @Test
  fun delete_all_is_confirmed_first() {
    // 4b #7
    var deleted = 0
    rule.setContent { RecordingsScreen(ui(), {}, {}, {}, { deleted++ }) }
    rule.onNodeWithTag("deleteAll").performClick()
    rule.onNodeWithText("やめる").performClick()
    assertEquals(0, deleted)
    rule.onNodeWithTag("deleteAll").performClick()
    rule.onNodeWithText("消す").performClick()
    assertEquals(1, deleted)
  }

  @Test
  fun the_trial_button_follows_the_trial() {
    var trial by mutableStateOf(TRIAL_IDLE)
    var taps = 0
    rule.setContent { RecordingsScreen(ui(trial = trial), { taps++ }, {}, {}, {}) }
    rule.onNodeWithText("試し録り").performClick()
    trial = TrialState(phase = TrialPhase.RECORDING)
    rule.onNodeWithText("止めて聞く").performClick()
    rule.onNodeWithTag("deleteAll").assertIsNotEnabled()
    trial = TrialState(device = intercom)
    rule.onNodeWithText("録ったマイク: インカム（M1-S PRO）").assertIsDisplayed()
    assertEquals(2, taps)
  }

  @Test
  fun nothing_can_be_started_while_a_question_runs() {
    rule.setContent { RecordingsScreen(ui(askBusy = true), {}, {}, {}, {}) }
    rule.onNodeWithTag("trial").assertIsNotEnabled()
    rule.onNodeWithTag("play-rec-2.pcm").assertIsNotEnabled()
    rule.onNodeWithTag("deleteAll").assertIsNotEnabled()
  }
}
