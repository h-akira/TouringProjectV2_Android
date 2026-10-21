package com.touringproject.v2.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

// SC-08 (slice 3 spec §7.2).
@RunWith(AndroidJUnit4::class)
class MemoScreenTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val saved = mutableListOf<String?>()
  private var closed = 0

  private fun show(initial: String? = null, result: Boolean = true) = rule.setContent {
    MemoScreen("会話 3 · 09:41 箱根町仙石原", initial, { saved += it; result }, { closed++ })
  }

  private fun back() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

  @Test
  fun a_memo_is_saved_trimmed_and_the_screen_closes() {
    show()
    rule.onNodeWithTag("memoTarget").assertTextEquals("会話 3 · 09:41 箱根町仙石原")
    rule.onNodeWithTag("memoInput").performTextReplacement("  また来たい  ")
    rule.onNodeWithTag("memoSave").performClick()
    rule.waitForIdle()
    assertEquals(listOf<String?>("また来たい"), saved)
    assertEquals(1, closed)
  }

  @Test
  fun saving_an_empty_memo_removes_it() {
    show(initial = "消す前")
    rule.onNodeWithTag("memoInput").performTextReplacement(" ")
    rule.onNodeWithTag("memoSave").performClick()
    rule.waitForIdle()
    assertEquals(listOf<String?>(null), saved)
  }

  @Test
  fun the_field_keeps_2000_characters_and_counts_them() {
    show()
    rule.onNodeWithTag("memoInput").performTextReplacement("あ".repeat(2100))
    rule.onNodeWithTag("memoCount").assertTextEquals("2000 / 2000字")
  }

  @Test
  fun closing_without_a_change_does_not_ask() {
    show(initial = "そのまま")
    rule.onNodeWithTag("memoClose").performClick()
    assertEquals(1, closed)
    rule.onNodeWithText("変更を破棄しますか？").assertDoesNotExist()
  }

  // US-D-04.004: a change is not thrown away without asking, by ✕ or by Back.
  @Test
  fun closing_with_a_change_asks_first_and_can_go_on_writing() {
    show(initial = "前のメモ")
    rule.onNodeWithTag("memoInput").performTextReplacement("書きかけ")
    rule.onNodeWithTag("memoClose").performClick()
    rule.onNodeWithText("変更を破棄しますか？").assertExists()
    rule.onNodeWithText("書き続ける").performClick()
    assertEquals(0, closed)
    back()
    rule.waitForIdle()
    rule.onNodeWithTag("memoDiscard").performClick()
    assertEquals(1, closed)
    assertEquals(emptyList<String?>(), saved)
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
    rule.onNodeWithText("書けない").assertExists()
  }

  @Test
  fun the_save_button_waits_for_the_save_in_progress() {
    val gate = CompletableDeferred<Boolean>()
    rule.setContent { MemoScreen("走行のメモ", null, { saved += it; gate.await() }, { closed++ }) }
    rule.onNodeWithTag("memoInput").performTextReplacement("一度だけ")
    rule.onNodeWithTag("memoSave").performClick()
    rule.onNodeWithTag("memoSave").performClick()
    gate.complete(true)
    rule.waitForIdle()
    assertEquals(listOf<String?>("一度だけ"), saved)
  }
}
