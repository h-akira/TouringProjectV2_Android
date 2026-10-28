package com.touringproject.v2.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.map.LatLon
import com.touringproject.v2.map.MapData
import com.touringproject.v2.map.MapPin
import com.touringproject.v2.map.PinKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
class RecordsScreensTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  /** Stands in for MapLibre: shows how many pins it was given and offers a button per pin. */
  private val fakeMap: MapSlot = { data, selected, onPin, modifier ->
    Column(modifier.testTag("map")) {
      Text("pins ${data.pins.size} selected $selected", Modifier.testTag("mapState"))
      data.pins.forEach { pin -> Button(onClick = { onPin(pin.id) }, Modifier.testTag("pin-${pin.id}")) { Text("${pin.number}") } }
    }
  }

  private val row = RideRowUi("r1", "箱根", "10月16日（金） · 08:58〜10:46", "52.8 km · 1:30 · 質問 2 · ⏸ 1", "保存済み", true)

  private fun detail(autoEnded: Boolean = false, recording: Boolean = false, memo: String? = null) = RideDetailUi(
    name = "箱根",
    summary = RideSummaryUi("10月16日（金）", "08:58〜10:46", "1:30", "52.8 km", "2", "一時停止 1回（18分）は走行時間に含めません", autoEnded),
    recording = recording,
    timeline = listOf(
      TimelineRow.Ask("a1", 1, "09:41", "箱根町仙石原", "右手の山は？", false),
      TimelineRow.Pause("⏸ 一時停止 09:44〜10:02（18分）"),
      TimelineRow.NewConversation,
      TimelineRow.Ask("a2", 2, "10:30", "御殿場市", "（聞き取れませんでした）", true),
    ),
    map = MapData(emptyList(), emptyList(), null, null, listOf(MapPin("a1", 1, LatLon(35.2, 139.0)), MapPin("a2", 2, LatLon(35.3, 138.9)))),
    cards = mapOf("a1" to PinCardUi("a1", "1 · 09:41 · 箱根町仙石原", "右手の山は？", "金時山です。")),
    memo = memo,
  )

  private fun back() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

  @Test
  fun the_list_shows_each_ride_under_its_month_and_opens_it() {
    val opened = mutableListOf<String>()
    rule.setContent { RideListScreen(listOf(RideSection("2026年10月", listOf(row))), { opened += it }) }
    rule.onNodeWithText("2026年10月").assertExists()
    rule.onNodeWithText("52.8 km · 1:30 · 質問 2 · ⏸ 1").assertExists()
    rule.onNodeWithTag("state-r1", useUnmergedTree = true).assertTextEquals("保存済み")
    rule.onNodeWithTag("ride-r1").performClick()
    assertEquals(listOf("r1"), opened)
  }

  // Slice 5 spec §7: the list says whose records these are, and the name opens SC-18.
  @Test
  fun the_list_shows_the_user_name_which_opens_the_user_screen() {
    var opened = 0
    rule.setContent { RideListScreen(emptyList(), {}, userName = "rider01", onUser = { opened++ }) }
    rule.onNodeWithTag("listUser").assertTextEquals("👤 rider01")
    rule.onNodeWithTag("listUser").performClick()
    assertEquals(1, opened)
  }

  @Test
  fun without_rides_the_list_says_how_to_record_one() {
    rule.setContent { RideListScreen(emptyList(), {}) }
    rule.onNodeWithText("まだ走行の記録がありません。ホームの「▶ 走行を開始」で記録します。").assertExists()
  }

  @Test
  fun the_detail_shows_the_summary_and_the_timeline_and_a_row_opens_its_ask() {
    val asked = mutableListOf<String>()
    rule.setContent { RideDetailScreen(detail(), fakeMap, true, {}, {}, { asked += it }, { true }, {}) }
    rule.onNodeWithTag("summaryQuestions").onChildAt(0).assertTextEquals("2")
    // Robolectric's screen is small: the timeline under the map is scrolled to each row.
    val timeline = rule.onNodeWithTag("timeline")
    timeline.performScrollToNode(hasText("⏸ 一時停止 09:44〜10:02（18分）"))
    timeline.performScrollToNode(hasText("↻ ここから新しい会話"))
    timeline.performScrollToNode(hasTestTag("ask-a2"))
    rule.onNodeWithTag("ask-a2").performClick()
    assertEquals(listOf("a2"), asked)
  }

  @Test
  fun a_tapped_pin_shows_its_card_which_opens_the_ask() {
    val asked = mutableListOf<String>()
    rule.setContent { RideDetailScreen(detail(), fakeMap, true, {}, {}, { asked += it }, { true }, {}) }
    rule.onNodeWithTag("pin-a1").performClick()
    rule.onNodeWithTag("mapState").assertTextEquals("pins 2 selected a1")
    rule.onNodeWithText("金時山です。").assertExists()
    rule.onNodeWithTag("pinCard").performClick()
    assertEquals(listOf("a1"), asked)
  }

  @Test
  fun the_pins_can_be_hidden_and_the_choice_goes_to_the_settings() {
    var shown by mutableStateOf(true)
    rule.setContent { RideDetailScreen(detail(), fakeMap, shown, { shown = it }, {}, {}, { true }, {}) }
    rule.onNodeWithTag("pin-a1").performClick()
    rule.onNodeWithTag("pinToggle").performClick()
    rule.onNodeWithTag("mapState").assertTextEquals("pins 0 selected null")
    rule.onNodeWithTag("pinCard").assertDoesNotExist()
  }

  @Test
  fun the_map_fills_the_screen_until_back_and_tells_so() {
    val full = mutableListOf<Boolean>()
    rule.setContent { RideDetailScreen(detail(), fakeMap, true, {}, {}, {}, { true }, { full += it }) }
    rule.onNodeWithTag("fullMap").performClick()
    rule.waitForIdle()
    rule.onNodeWithTag("timeline").assertDoesNotExist()
    rule.onNodeWithTag("map").assertExists()
    back()
    rule.waitForIdle()
    rule.onNodeWithTag("timeline").assertExists()
    assertEquals(listOf(false, true, false), full)
  }

  @Test
  fun the_menu_renames_the_ride_but_not_to_an_empty_name() {
    val names = mutableListOf<String>()
    rule.setContent { RideDetailScreen(detail(), fakeMap, true, {}, {}, {}, { names += it; true }, {}) }
    rule.onNodeWithTag("rideMenu").performClick()
    rule.onNodeWithTag("renameRide").performClick()
    rule.onNodeWithTag("renameInput").performTextReplacement("  ")
    rule.onNodeWithTag("renameGo").assertIsNotEnabled()
    rule.onNodeWithTag("renameInput").performTextReplacement(" 御殿場 ")
    rule.onNodeWithTag("renameGo").performClick()
    assertEquals(listOf("御殿場"), names)
  }

  // Android #46: a name the device could not save is told, not silently lost.
  @Test
  fun a_rename_that_could_not_be_saved_is_told() {
    rule.setContent { RideDetailScreen(detail(), fakeMap, true, {}, {}, {}, { false }, {}) }
    rule.onNodeWithTag("rideMenu").performClick()
    rule.onNodeWithTag("renameRide").performClick()
    rule.onNodeWithTag("renameInput").performTextReplacement("御殿場")
    rule.onNodeWithTag("renameGo").performClick()
    rule.waitForIdle()
    assertEquals("名前を変えられませんでした", ShadowToast.getTextOfLatestToast())
  }

  @Test
  fun a_ride_ended_by_its_longest_time_says_so() {
    rule.setContent { RideDetailScreen(detail(autoEnded = true), fakeMap, true, {}, {}, {}, { true }, {}) }
    rule.onNodeWithTag("autoEnded").assertExists()
  }

  @Test
  fun the_ask_detail_moves_to_its_neighbours_and_tells_why_an_ask_failed() {
    val moves = mutableListOf<String>()
    val failed = AskDetailUi("会話 2 / 2", "10月16日（金） 10:30", "静岡県御殿場市", "停車中", "🎤 声で質問", null, null, "時間内に答えが届きませんでした", "a1", null)
    rule.setContent { AskDetailScreen(failed, null, fakeMap, "a2", {}, { moves += it }) }
    rule.onNodeWithText("会話 2 / 2").assertExists()
    rule.onNodeWithTag("askFailure").assertTextEquals("答えられませんでした: 時間内に答えが届きませんでした")
    rule.onNodeWithText("この質問の場所は分かりません。").assertExists()
    rule.onNodeWithTag("askNext").assertIsNotEnabled()
    rule.onNodeWithTag("askPrev").assertIsEnabled().performClick()
    assertEquals(listOf("a1"), moves)
  }

  @Test
  fun the_ask_detail_shows_the_question_and_answer_with_the_asks_place_on_the_map() {
    val answered = AskDetailUi("会話 1 / 2", "10月16日（金） 09:41", "神奈川県箱根町仙石原", "北東へ走行中（右手 南東・左手 北西）", "🎤 声で質問", "右手の山は？", "金時山です。", null, null, "a2")
    val map = MapData(emptyList(), emptyList(), null, null, listOf(MapPin("a1", 1, LatLon(35.2, 139.0))), faint = true, focus = LatLon(35.2, 139.0))
    rule.setContent { AskDetailScreen(answered, map, fakeMap, "a1", {}, {}) }
    rule.onNodeWithTag("askQuestion").assertTextEquals("右手の山は？")
    rule.onNodeWithTag("askAnswer").assertTextEquals("金時山です。")
    rule.onNodeWithTag("mapState").assertTextEquals("pins 1 selected a1")
    rule.onNodeWithText("北東へ走行中（右手 南東・左手 北西）").assertExists()
  }

  // Final review: the map is the same one before and after full screen, so the rider's pan and zoom stay.
  @Test
  fun the_map_is_not_made_again_when_it_fills_the_screen_and_comes_back() {
    var made = 0
    val countingMap: MapSlot = { data, selected, onPin, modifier ->
      DisposableEffect(Unit) {
        made++
        onDispose {}
      }
      fakeMap(data, selected, onPin, modifier)
    }
    rule.setContent { RideDetailScreen(detail(), countingMap, true, {}, {}, {}, { true }, {}) }
    rule.onNodeWithTag("fullMap").performClick()
    rule.waitForIdle()
    back()
    rule.waitForIdle()
    rule.onNodeWithTag("timeline").assertExists()
    assertEquals(1, made)
  }

  // Final review: inside the app's NavDisplay, Back from the full map closes the full map, not the ride detail.
  @Test
  fun back_from_the_full_map_stays_on_the_ride_inside_the_navigation() {
    val stack = mutableStateListOf("list", "ride")
    rule.setContent {
      NavDisplay(
        backStack = stack,
        onBack = { stack.removeAt(stack.lastIndex) },
        entryProvider = { key ->
          NavEntry(key) {
            if (key == "list") Text("list") else RideDetailScreen(detail(), fakeMap, true, {}, {}, {}, { true }, {})
          }
        },
      )
    }
    rule.onNodeWithTag("fullMap").performClick()
    rule.waitForIdle()
    back()
    rule.waitForIdle()
    assertEquals(listOf("list", "ride"), stack.toList())
    rule.onNodeWithTag("timeline").assertExists()
  }

  // US-D-04.005: SC-05 marks a ride that has a memo.
  @Test
  fun the_list_marks_a_ride_with_a_memo() {
    rule.setContent { RideListScreen(listOf(RideSection("2026年10月", listOf(row, row.copy(id = "r2", memo = true)))), {}) }
    rule.onNodeWithTag("rideMemoMark-r2", useUnmergedTree = true).assertExists()
    rule.onNodeWithTag("rideMemoMark-r1", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun the_ride_shows_its_memo_which_opens_sc_08_and_without_one_offers_to_write_it() {
    var memos = 0
    var memo by mutableStateOf<String?>(null)
    rule.setContent { RideDetailScreen(detail(memo = memo), fakeMap, true, {}, {}, {}, { true }, {}, onMemo = { memos++ }) }
    rule.onNodeWithTag("timeline").performScrollToNode(hasTestTag("rideMemoAdd"))
    rule.onNodeWithTag("rideMemoAdd").performClick()
    memo = "峠の茶屋が良かった"
    rule.waitForIdle()
    rule.onNodeWithTag("timeline").performScrollToNode(hasTestTag("rideMemo"))
    rule.onNodeWithTag("rideMemo").performClick()
    rule.onNodeWithTag("rideMenu").performClick()
    rule.onNodeWithTag("rideMemoMenu").performClick()
    assertEquals(3, memos)
    rule.onNodeWithText("峠の茶屋が良かった").assertExists()
  }

  @Test
  fun the_timeline_marks_an_ask_with_a_memo() {
    val withMemo = detail().let { it.copy(timeline = listOf(TimelineRow.Ask("a1", 1, "09:41", "箱根町仙石原", "右手の山は？", false, memo = true))) }
    rule.setContent { RideDetailScreen(withMemo, fakeMap, true, {}, {}, {}, { true }, {}) }
    rule.onNodeWithTag("timeline").performScrollToNode(hasTestTag("ask-a1"))
    rule.onNodeWithText("09:41 · 箱根町仙石原 · 📝").assertExists()
  }

  // Android #65: as the mock draws it, an unreached ask's row is marked after its memo's mark.
  @Test
  fun the_timeline_marks_an_unreached_ask() {
    val row = TimelineRow.Ask("u1", 1, "10:05", "住所は送信後", UNREACHED_TEXT, true, memo = true, unreached = true)
    rule.setContent { RideDetailScreen(detail().copy(timeline = listOf(row)), fakeMap, true, {}, {}, {}, { true }, {}) }
    rule.onNodeWithTag("timeline").performScrollToNode(hasTestTag("ask-u1"))
    rule.onNodeWithText("10:05 · 住所は送信後 · 📝 · 📵").assertExists()
  }

  // US-D-06.002, US-D-06.003: an ended ride is deleted after DL-01 says what goes.
  @Test
  fun an_ended_ride_is_deleted_after_the_confirmation() {
    var deletes = 0
    rule.setContent { RideDetailScreen(detail(), fakeMap, true, {}, {}, {}, { true }, {}, onDelete = { deletes++; true }) }
    rule.onNodeWithTag("rideMenu").performClick()
    rule.onNodeWithTag("deleteRide").performClick()
    rule.onNodeWithText("ルート・走行のメモと、その走行の質問・地点メモが削除されます。元に戻せません。").assertExists()
    rule.onNodeWithTag("deleteCancel").performClick()
    assertEquals(0, deletes)
    rule.onNodeWithTag("rideMenu").performClick()
    rule.onNodeWithTag("deleteRide").performClick()
    rule.onNodeWithTag("deleteGo").performClick()
    rule.waitForIdle()
    assertEquals(1, deletes)
  }

  // Slice 3 spec §2: the ride recording or paused is ended first.
  @Test
  fun the_ride_recording_cannot_be_deleted() {
    rule.setContent { RideDetailScreen(detail(recording = true), fakeMap, true, {}, {}, {}, { true }, {}) }
    rule.onNodeWithTag("rideMenu").performClick()
    rule.onNodeWithTag("renameRide").assertExists()
    rule.onNodeWithTag("deleteRide").assertDoesNotExist()
  }

  @Test
  fun a_ride_that_could_not_be_deleted_is_told() {
    rule.setContent { RideDetailScreen(detail(), fakeMap, true, {}, {}, {}, { true }, {}, onDelete = { false }) }
    rule.onNodeWithTag("rideMenu").performClick()
    rule.onNodeWithTag("deleteRide").performClick()
    rule.onNodeWithTag("deleteGo").performClick()
    rule.waitForIdle()
    assertEquals("削除できませんでした", ShadowToast.getTextOfLatestToast())
  }

  private val answered = AskDetailUi("会話 1 / 2", "10月16日（金） 09:41", "神奈川県箱根町仙石原", "停車中", "🎤 声で質問", "右手の山は？", "金時山です。", null, null, "a2")

  @Test
  fun the_ask_shows_its_memo_and_opens_sc_08() {
    var memos = 0
    rule.setContent { AskDetailScreen(answered.copy(memo = "給油した"), null, fakeMap, "a1", {}, {}, onMemo = { memos++ }) }
    rule.onNodeWithText("給油した").assertExists()
    rule.onNodeWithTag("askMemo").performScrollTo().performClick()
    assertEquals(1, memos)
  }

  @Test
  fun an_ask_without_a_memo_offers_to_write_one() {
    var memos = 0
    rule.setContent { AskDetailScreen(answered, null, fakeMap, "a1", {}, {}, onMemo = { memos++ }) }
    rule.onNodeWithTag("askMemoAdd").performScrollTo().performClick()
    assertEquals(1, memos)
  }

  // US-D-06.001: an ask is deleted after DL-01.
  @Test
  fun an_ask_is_deleted_after_the_confirmation() {
    var deletes = 0
    rule.setContent { AskDetailScreen(answered, null, fakeMap, "a1", {}, {}, onDelete = { deletes++; true }) }
    rule.onNodeWithTag("deleteAsk").performScrollTo().performClick()
    rule.onNodeWithText("質問・回答・メモが削除されます。元に戻せません。").assertExists()
    rule.onNodeWithTag("deleteGo").performClick()
    rule.waitForIdle()
    assertEquals(1, deletes)
  }

  // Final review: the ride deleted, its detail turns "not found" while the deletion is still finishing; going back to
  // SC-05 must not be cancelled with it.
  @Test
  fun the_deletion_finishes_even_when_the_ride_is_gone_from_the_screen_meanwhile() {
    var ui by mutableStateOf<RideDetailUi?>(detail())
    var finished = 0
    rule.setContent {
      RideDetailScreen(ui, fakeMap, true, {}, {}, {}, { true }, {}, onDelete = {
        ui = null
        kotlinx.coroutines.delay(100)
        finished++
        true
      })
    }
    rule.onNodeWithTag("rideMenu").performClick()
    rule.onNodeWithTag("deleteRide").performClick()
    rule.onNodeWithTag("deleteGo").performClick()
    rule.mainClock.advanceTimeBy(500)
    rule.waitForIdle()
    assertEquals(1, finished)
  }

  // US-D-08: a point memo's row in the timeline opens it in SC-19.
  @Test
  fun a_point_memo_in_the_timeline_opens_it() {
    val opened = mutableListOf<String>()
    val ui = detail().copy(timeline = listOf(TimelineRow.Note("n1", 1, "09:50", "御殿場市新橋", "展望スペース")))
    rule.setContent { RideDetailScreen(ui, fakeMap, true, {}, {}, {}, { true }, {}, onNote = { opened += it }) }
    rule.onNodeWithTag("timeline").performScrollToNode(hasTestTag("note-n1"))
    rule.onNodeWithText("09:50 · 御殿場市新橋 · 📝 地点メモ").assertExists()
    rule.onNodeWithTag("note-n1").performClick()
    assertEquals(listOf("n1"), opened)
  }

  private fun withNote() = detail().let { d ->
    d.copy(
      map = d.map.copy(pins = d.map.pins + MapPin("n1", 1, LatLon(35.25, 138.95), kind = PinKind.NOTE)),
      cards = d.cards + ("n1" to PinCardUi("n1", "1 · 09:50 · 御殿場市新橋 · 📝 地点メモ", "展望スペース", null, PinKind.NOTE)),
      summary = d.summary.copy(notes = "📝 地点メモ 1件"),
    )
  }

  // US-D-08: a point memo's green pin shows its card, and the card opens SC-19.
  @Test
  fun a_point_memos_pin_shows_its_card_which_opens_the_memo() {
    val opened = mutableListOf<String>()
    val asked = mutableListOf<String>()
    rule.setContent { RideDetailScreen(withNote(), fakeMap, true, {}, {}, { asked += it }, { true }, {}, onNote = { opened += it }) }
    rule.onNodeWithText("📝 地点メモ 1件").assertExists()
    rule.onNodeWithTag("pin-n1").performClick()
    rule.onNodeWithText("展望スペース").assertExists()
    rule.onNodeWithText("開く ›").assertExists()
    rule.onNodeWithTag("pinCard").performClick()
    assertEquals(listOf("n1"), opened)
    assertEquals(emptyList<String>(), asked)
  }

  // US-D-02: "💬 質問" and "📝 地点メモ" show or hide their own pins; each choice goes to the settings.
  @Test
  fun the_asks_and_the_point_memos_pins_are_shown_or_hidden_apart() {
    var asks by mutableStateOf(true)
    var notes by mutableStateOf(true)
    rule.setContent {
      RideDetailScreen(withNote(), fakeMap, asks, { asks = it }, {}, {}, { true }, {}, showNotes = notes, onShowNotes = { notes = it })
    }
    rule.onNodeWithTag("mapState").assertTextEquals("pins 3 selected null")
    rule.onNodeWithTag("pin-n1").performClick()
    rule.onNodeWithTag("notePinToggle").performClick()
    rule.onNodeWithTag("mapState").assertTextEquals("pins 2 selected null")
    rule.onNodeWithTag("pinCard").assertDoesNotExist()
    rule.onNodeWithTag("pinToggle").performClick()
    rule.onNodeWithTag("mapState").assertTextEquals("pins 0 selected null")
    // The timeline is shown whatever the map shows.
    rule.onNodeWithTag("timeline").performScrollToNode(hasTestTag("ask-a1"))
    assertEquals(false to false, asks to notes)
  }

  // Slice 4 spec §4.3: an unreached ask shows that it never reached the Backend instead of the question and answer,
  // and offers its memo for what the rider wanted to know.
  @Test
  fun an_unreached_ask_says_so_instead_of_a_question_and_offers_its_memo() {
    val unreached = AskDetailUi(
      "会話 2 / 2", "10月16日（金） 10:05", "住所は送ったあとに付きます", "停車中", "🎤 声で質問", null, null, null, "a1", null, unreached = true,
    )
    var memos = 0
    rule.setContent { AskDetailScreen(unreached, null, fakeMap, "u1", {}, {}, onMemo = { memos++ }) }
    rule.onNodeWithTag("askUnreached").assertTextEquals("📵 通信できず、質問できなかった")
    rule.onNodeWithText("質問の内容は残していません。何を聞きたかったかは、メモに書けます。").assertExists()
    rule.onNodeWithTag("askQuestion").assertDoesNotExist()
    rule.onNodeWithTag("askAnswer").assertDoesNotExist()
    rule.onNodeWithText("＋ メモを追加").performScrollTo().performClick()
    assertEquals(1, memos)
  }
}
