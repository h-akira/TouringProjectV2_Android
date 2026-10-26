package com.touringproject.v2.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.api.CloudDeleted
import com.touringproject.v2.api.CloudPage
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.Phase
import com.touringproject.v2.ask.RecordingMeta
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.recordings.TRIAL_IDLE
import com.touringproject.v2.recordings.TrialPhase
import com.touringproject.v2.recordings.TrialState
import com.touringproject.v2.records.SaveSummary
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.Settings
import com.touringproject.v2.sync.Peek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    rule.setContent { HomeScreen(HomeUi("rider01", null, "新しい会話です"), { taps++ }, {}, {}, {}) }
    rule.onNodeWithTag("tapToTalk").performClick()
    assertEquals(1, taps)
  }

  // Slice 2 spec §4.7: without a recording ride the button stays where it is but cannot be pressed, and says why.
  @Test
  fun without_a_recording_ride_tap_to_talk_cannot_be_pressed_and_the_hint_says_why() {
    var taps = 0
    rule.setContent { HomeScreen(HomeUi("rider01", null, "新しい会話です", askHint = "再開すると、インカムのボタンで話せます"), { taps++ }, {}, {}, {}) }
    rule.onNodeWithTag("tapToTalk").assertIsNotEnabled().performClick()
    assertEquals(0, taps)
    rule.onNodeWithTag("askHint").assertTextEquals("再開すると、インカムのボタンで話せます")
  }

  // Slice 3 spec §7.1: "📝 地点メモ" is there only while a ride records or is paused, and opens SC-19 at once.
  @Test
  fun the_point_memo_button_is_there_only_while_a_ride_records_or_is_paused() {
    var notes = 0
    var riding by mutableStateOf(false)
    rule.setContent { HomeScreen(HomeUi("rider01", null, "新しい会話です"), {}, {}, {}, {}, onNote = if (riding) ({ notes++ }) else null) }
    rule.onNodeWithTag("newNote").assertDoesNotExist()
    riding = true
    rule.onNodeWithTag("newNote").performClick()
    assertEquals(1, notes)
  }

  @Test
  fun while_a_ride_records_the_hint_says_the_intercom_is_ready() {
    rule.setContent { HomeScreen(HomeUi("rider01", null, "新しい会話です"), {}, {}, {}, {}) }
    rule.onNodeWithTag("tapToTalk").assertIsEnabled()
    rule.onNodeWithTag("askHint").assertTextEquals("インカムのボタンで話せます")
  }

  @Test
  fun the_banner_leads_to_its_setting() {
    var opened: Banner? = null
    rule.setContent { HomeScreen(HomeUi("rider01", Banner.NO_KEY, "新しい会話です"), {}, {}, { opened = it }, {}) }
    rule.onNodeWithText(Banner.NO_KEY.text).performClick()
    assertEquals(Banner.NO_KEY, opened)
  }

  @Test
  fun the_low_volume_band_closes_with_its_cross_and_leads_nowhere() {
    var opened: Banner? = null
    var dismissed = 0
    rule.setContent { HomeScreen(HomeUi("rider01", Banner.LOW_VOLUME, "新しい会話です"), {}, {}, { opened = it }, {}, onDismissVolume = { dismissed++ }) }
    rule.onNodeWithText(Banner.LOW_VOLUME.text).performClick()
    rule.onNodeWithTag("bandClose").performClick()
    assertNull(opened)
    assertEquals(1, dismissed)
  }

  // Android #28: the ask is lost either way, so the band closes once the rider has seen it.
  @Test
  fun the_band_of_an_ask_that_could_not_be_written_closes_with_its_cross() {
    var opened: Banner? = null
    var dismissed = 0
    rule.setContent {
      HomeScreen(HomeUi("rider01", Banner.ASK_STORE_FAILED, "新しい会話です"), {}, {}, { opened = it }, {}, onDismissAskFailure = { dismissed++ })
    }
    rule.onNodeWithText("書けなかった質問があります").performClick()
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
    rule.setContent { HomeScreen(HomeUi("rider01", null, "新しい会話です"), {}, {}, {}, { resets++ }) }
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

  private fun peeked(name: String, exists: Boolean) =
    Peek(name, CloudPage(if (exists) listOf(CloudDeleted("note", "n1", "r1", "2026-10-22T03:00:00Z")) else emptyList(), null))

  @Test
  fun an_unusable_user_name_is_refused_before_anything_is_read() {
    val peeks = mutableListOf<String>()
    rule.setContent { UserScreen("rider01", null, false, null, { peeks += it; null }, {}, {}) }
    rule.onNodeWithTag("userInput").performTextInput("No!")
    rule.onNodeWithTag("changeUser").performClick()
    rule.onNodeWithTag("userError").assertTextEquals("英小文字と数字の3〜20文字にしてください")
    rule.onNodeWithTag("userInput").performTextClearance()
    rule.onNodeWithTag("userInput").performTextInput("rider01")
    rule.onNodeWithTag("changeUser").performClick()
    rule.onNodeWithTag("userError").assertTextEquals("今のユーザー名と同じです")
    assertEquals(emptyList<String>(), peeks)
  }

  // Slice 4 spec §6.4: DL-03 is worded by whether the new name has records, and the change starts once confirmed.
  @Test
  fun dl03_tells_a_new_name_from_one_with_records() {
    val changed = mutableListOf<Peek>()
    rule.setContent { UserScreen("rider01", null, false, null, { peeked(it, exists = it == "taro01") }, { changed += it }, {}) }
    rule.onNodeWithTag("userInput").performTextInput("hanako2")
    rule.onNodeWithTag("changeUser").performClick()
    rule.onNodeWithText("ユーザー名を変えますか？").assertExists()
    rule.onNodeWithTag("userDialogText").assertTextEquals(
      "「hanako2」は新しいユーザー名です。今の「rider01」の記録は引き継がれず、記録が空の状態から始まります。元のユーザー名に戻せば、今の記録はまた見られます。",
    )
    rule.onNodeWithText("やめる").performClick()
    rule.onNodeWithTag("userInput").performTextClearance()
    rule.onNodeWithTag("userInput").performTextInput("taro01")
    rule.onNodeWithTag("changeUser").performClick()
    rule.onNodeWithText("既にあるユーザー名です").assertExists()
    rule.onNodeWithTag("confirmUser").assertTextEquals("切り替える")
    rule.onNodeWithTag("confirmUser").performClick()
    assertEquals(listOf("taro01"), changed.map { it.userName })
  }

  @Test
  fun a_name_that_cannot_be_read_is_not_changed_to() {
    rule.setContent { UserScreen("rider01", null, false, null, { null }, {}, {}) }
    rule.onNodeWithTag("userInput").performTextInput("taro01")
    rule.onNodeWithTag("changeUser").performClick()
    rule.onNodeWithTag("userError").assertTextEquals("読み込めませんでした")
    rule.onNodeWithText("既にあるユーザー名です").assertDoesNotExist()
  }

  // US-E-06.008: not while a ride records or something is left to send, nor during a read; SC-14 is a tap away.
  @Test
  fun the_name_cannot_change_while_blocked_or_reading() {
    var block by mutableStateOf<UserBlock?>(UserBlock.UNSENT)
    var reading by mutableStateOf(false)
    var toData = 0
    rule.setContent { UserScreen("rider01", block, reading, "ユーザー名を変えました", { null }, {}, { toData += 1 }) }
    rule.onNodeWithTag("changeUser").assertIsNotEnabled()
    rule.onNodeWithTag("userBlock").assertTextEquals("未送信の記録があります。先に「データの保存」で送ってください")
    rule.onNodeWithTag("toData").performClick()
    assertEquals(1, toData)
    block = UserBlock.RIDE
    rule.onNodeWithTag("userBlock").assertTextEquals("走行を終えてから変えられます")
    rule.onNodeWithTag("toData").assertDoesNotExist()
    block = null
    reading = true
    rule.onNodeWithTag("changeUser").assertIsNotEnabled()
    rule.onNodeWithText("読み込んでいます…").assertExists()
    rule.onNodeWithTag("userMessage").assertTextEquals("ユーザー名を変えました")
  }

  private val data = DataUi(SaveSummary(rides = 3, asks = 19), "最終保存 10:22", false, false, false, false, "rider01", null, 10)

  // Slice 4 spec §7.1: saved, with the last save, and the counts.
  @Test
  fun sc14_tells_all_is_saved_and_what_is_kept() {
    rule.setContent { DataScreen(data, {}, {}, {}) }
    rule.onNodeWithTag("saveState").assertTextEquals("● すべてクラウドに保存済み")
    rule.onNodeWithText("最終保存 10:22").assertExists()
    rule.onNodeWithTag("saveNow").assertDoesNotExist()
    rule.onNodeWithText("19").assertExists()
    rule.onNodeWithText("10 / 10").performScrollTo().assertExists()
  }

  @Test
  fun sc14_tells_what_is_left_and_sends_it_now() {
    var saves = 0
    var sending by mutableStateOf(false)
    rule.setContent { DataScreen(data.copy(summary = SaveSummary(unreached = 1, unsentRides = 2), sending = sending), { saves += 1 }, {}, {}) }
    rule.onNodeWithTag("saveState").assertTextEquals("● 未送信の記録があります")
    rule.onNodeWithTag("unsentBreakdown").assertTextEquals("質問できなかった地点 1件・走行 2本。通信できるようになると自動で送ります。")
    rule.onNodeWithTag("saveNow").performClick()
    assertEquals(1, saves)
    sending = true
    rule.onNodeWithTag("saveNow").assertIsNotEnabled()
    rule.onNodeWithText("送っています…").assertExists()
  }

  // Reading again waits for the ride's end (decided in plan 3), and for a read under way.
  @Test
  fun sc14_reads_the_cloud_again_unless_a_ride_records_or_a_read_runs() {
    var rereads = 0
    var ui by mutableStateOf(data)
    rule.setContent { DataScreen(ui, {}, {}, { rereads += 1 }) }
    rule.onNodeWithTag("reread").performClick()
    assertEquals(1, rereads)
    ui = data.copy(reading = true)
    rule.onNodeWithTag("reread").assertIsNotEnabled()
    rule.onNodeWithText("読み込んでいます…").assertExists()
    ui = data.copy(active = true, recording = true, message = "12件の記録を読み込みました")
    rule.onNodeWithTag("reread").assertIsNotEnabled()
    rule.onNodeWithText("走行を終えてから読み直せます").assertExists()
    rule.onNodeWithTag("recordingNote").assertExists()
    rule.onNodeWithTag("dataMessage").assertTextEquals("12件の記録を読み込みました")
  }

  @Test
  fun the_settings_list_leads_to_the_data_screen() {
    var opened = false
    rule.setContent { SettingsScreen(Settings("rider01"), null, {}, {}, {}, {}, {}, {}, "0.2.0", saved = "未送信あり", onData = { opened = true }) }
    rule.onNodeWithText("未送信あり").performClick()
    assertTrue(opened)
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
    rule.setContent {
      SettingsScreen(
        Settings("rider01", answerVolumeDb = 3, maxRecordingHours = 12), null, {}, {}, { opened += "ride" }, {}, { opened += "volume" }, { opened += "recordings" }, "0.2.0",
      )
    }
    rule.onNodeWithText("最長 12時間").performScrollTo().performClick()
    rule.onNodeWithText("+3dB").performScrollTo().performClick()
    rule.onNodeWithText("録音の確認").performScrollTo().performClick()
    assertEquals(listOf("ride", "volume", "recordings"), opened)
  }

  // Slice 5 spec §6: SC-09's row says SC-11's values and opens it.
  @Test
  fun the_settings_list_leads_to_the_recording_settings() {
    var opened = false
    rule.setContent {
      SettingsScreen(Settings("rider01", recordLimitSeconds = 45, startCueMs = 0), null, {}, {}, {}, {}, {}, {}, "0.2.0", onRecordingSettings = { opened = true })
    }
    rule.onNodeWithText("録音の設定").assertExists()
    rule.onNodeWithText("上限 45秒 · 合図 なし").performScrollTo().performClick()
    assertTrue(opened)
  }

  // SC-11: the limit goes in 5 s steps from 10 to 60 s, and the cue is one of six lengths.
  @Test
  fun the_recording_limit_steps_by_5_seconds_within_10_to_60_and_the_cue_is_chosen() {
    var settings by mutableStateOf(Settings("rider01"))
    var resets = 0
    rule.setContent { RecordingSettingsScreen(settings, { settings = it }, { resets++ }) }
    rule.onNodeWithTag("limitValue").assertTextEquals("20秒")
    rule.onNodeWithTag("limitUp").performClick()
    rule.onNodeWithTag("limitValue").assertTextEquals("25秒")
    settings = settings.copy(recordLimitSeconds = 60)
    rule.onNodeWithTag("limitUp").assertIsNotEnabled()
    settings = settings.copy(recordLimitSeconds = 10)
    rule.onNodeWithTag("limitDown").assertIsNotEnabled()
    rule.onNodeWithTag("limitUp").assertIsEnabled()
    for ((ms, label) in listOf(0 to "なし", 500 to "0.5秒", 1_000 to "1秒", 1_500 to "1.5秒", 2_000 to "2秒", 3_000 to "3秒")) {
      rule.onNodeWithTag("cue-$ms").assertTextEquals(label)
    }
    rule.onNodeWithTag("cue-3000").performClick()
    assertEquals(3_000, settings.startCueMs)
    rule.onNodeWithTag("recordingDefaults").performClick()
    assertEquals(1, resets)
  }

  @Test
  fun the_settings_list_shows_the_app_version_as_a_row_that_leads_nowhere() {
    rule.setContent { SettingsScreen(Settings("rider01"), null, {}, {}, {}, {}, {}, {}, "0.2.0") }
    rule.onNodeWithText("バージョン").assertExists()
    rule.onNodeWithText("0.2.0").assertExists()
    rule.onNodeWithTag("versionRow").assertHasNoClickAction()
  }

  @Test
  fun the_longest_recording_time_is_chosen_from_four_steps() {
    var updated: Settings? = null
    rule.setContent { RideRecordingScreen(Settings("rider01"), { updated = it }) }
    rule.onNodeWithText("連続して記録できる最長時間").assertExists()
    for (hours in listOf(6, 12, 24, 48)) rule.onNodeWithTag("maxHours-$hours").assertExists()
    rule.onNodeWithTag("maxHours-6").performClick()
    assertEquals(6, updated!!.maxRecordingHours)
  }

  @Test
  fun the_tab_bar_names_the_three_tabs_and_tells_which_was_pressed() {
    val pressed = mutableListOf<Tab>()
    rule.setContent { TabBar(Tab.HOME) { pressed += it } }
    rule.onNodeWithText("ホーム").assertExists()
    rule.onNodeWithText("記録").performClick()
    rule.onNodeWithText("設定").performClick()
    assertEquals(listOf(Tab.RECORDS, Tab.SETTINGS), pressed)
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
