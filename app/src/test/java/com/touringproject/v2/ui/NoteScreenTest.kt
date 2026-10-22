package com.touringproject.v2.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

// SC-19 with text only (slice 3 spec §7.3); voice input comes with plan 4.
@RunWith(AndroidJUnit4::class)
class NoteScreenTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val header = NoteHeaderUi("10月22日（木） 10:24 · 今いる地点", "📍 住所は送ったあとに付きます", "走行「箱根」に入ります")
  private val saved = mutableListOf<String>()
  private var closed = 0
  private var deletes = 0

  private fun show(initial: String? = null, result: Boolean = true, deleted: Boolean? = null) = rule.setContent {
    NoteScreen(header, initial, { saved += it; result }, { closed++ }, onDelete = deleted?.let { d -> { deletes++; d } })
  }

  @Test
  fun the_top_says_when_where_and_for_which_ride_and_there_is_no_voice_input_yet() {
    show()
    rule.onNodeWithTag("noteTime").assertTextEquals("10月22日（木） 10:24 · 今いる地点")
    rule.onNodeWithTag("notePlace").assertTextEquals("📍 住所は送ったあとに付きます")
    rule.onNodeWithTag("noteRide").assertTextEquals("走行「箱根」に入ります")
    rule.onNodeWithText("🎤 声で入力").assertDoesNotExist()
  }

  // SC-19: an empty memo cannot be saved.
  @Test
  fun an_empty_memo_cannot_be_saved_and_a_written_one_is_saved_trimmed() {
    show()
    rule.onNodeWithTag("memoSave").assertIsNotEnabled()
    rule.onNodeWithTag("memoInput").performTextReplacement("  ")
    rule.onNodeWithTag("memoSave").assertIsNotEnabled()
    rule.onNodeWithTag("memoInput").performTextReplacement(" 展望スペース ")
    rule.onNodeWithTag("memoSave").assertIsEnabled().performClick()
    rule.waitForIdle()
    assertEquals(listOf("展望スペース"), saved)
    assertEquals(1, closed)
  }

  @Test
  fun the_field_keeps_2000_characters() {
    show()
    rule.onNodeWithTag("memoInput").performTextReplacement("あ".repeat(2100))
    rule.onNodeWithTag("memoCount").assertTextEquals("2000 / 2000字")
  }

  @Test
  fun closing_with_a_change_asks_first() {
    show(initial = "前のメモ", deleted = true)
    rule.onNodeWithTag("memoInput").performTextReplacement("書きかけ")
    rule.onNodeWithTag("memoClose").performClick()
    rule.onNodeWithText("変更を破棄しますか？").assertExists()
    rule.onNodeWithTag("memoDiscard").performClick()
    assertEquals(1, closed)
    assertEquals(emptyList<String>(), saved)
  }

  // Slice 3 spec §8: a memo that could not be written is told, and what was typed stays.
  @Test
  fun a_memo_that_cannot_be_saved_is_told_and_kept() {
    show(result = false)
    rule.onNodeWithTag("memoInput").performTextReplacement("書けない")
    rule.onNodeWithTag("memoSave").performClick()
    rule.waitForIdle()
    assertEquals(0, closed)
    assertEquals("保存できませんでした", ShadowToast.getTextOfLatestToast())
  }

  // A memo opened from Home is not kept yet, so there is nothing to delete.
  @Test
  fun a_new_memo_has_no_delete() {
    show()
    rule.onNodeWithTag("deleteNote").assertDoesNotExist()
  }

  // DL-01: a kept memo is deleted after the confirmation, and the screen goes back to where it was opened from.
  @Test
  fun a_kept_memo_is_deleted_after_the_confirmation() {
    show(initial = "消すメモ", deleted = true)
    rule.onNodeWithTag("deleteNote").performClick()
    rule.onNodeWithText("この地点メモを削除しますか？").assertExists()
    rule.onNodeWithText("メモが削除されます。元に戻せません。").assertExists()
    rule.onNodeWithTag("deleteCancel").performClick()
    assertEquals(0, deletes)
    rule.onNodeWithTag("deleteNote").performClick()
    rule.onNodeWithTag("deleteGo").performClick()
    rule.waitForIdle()
    assertEquals(1, deletes)
    assertEquals(1, closed)
  }

  @Test
  fun a_memo_that_could_not_be_deleted_is_told_and_stays() {
    show(initial = "消えないメモ", deleted = false)
    rule.onNodeWithTag("deleteNote").performClick()
    rule.onNodeWithTag("deleteGo").performClick()
    rule.waitForIdle()
    assertEquals(0, closed)
    assertEquals("削除できませんでした", ShadowToast.getTextOfLatestToast())
  }
}
