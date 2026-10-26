package com.touringproject.v2.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.ask.TextAskOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5 spec §5.2 and §5.3: Home's text sheet.
@RunWith(AndroidJUnit4::class)
class TextAskSheetTest {
  @get:Rule val rule = createComposeRule()

  private var sheet by mutableStateOf(TextSheet(open = true))
  private val sent = mutableListOf<String>()
  private var dismissed = 0

  private fun show() = rule.setContent {
    TextAskSheet(sheet, onText = { sheet = sheet.copy(text = it) }, onSend = { sent += it }, onDismiss = { dismissed++ })
  }

  @Test
  fun a_blank_text_cannot_be_asked_and_a_question_goes_trimmed() {
    show()
    rule.onNodeWithTag("textAskLeft").assertTextEquals("残り 500字 · 回答は読み上げます")
    rule.onNodeWithTag("textAskSend").assertIsNotEnabled()
    rule.onNodeWithTag("textAskInput").performTextInput("  \n ")
    rule.onNodeWithTag("textAskSend").assertIsNotEnabled()
    rule.onNodeWithTag("textAskInput").performTextInput("右手の山は？")
    rule.onNodeWithTag("textAskLeft").assertTextEquals("残り 490字 · 回答は読み上げます")
    rule.onNodeWithTag("textAskSend").assertIsEnabled().performClick()
    assertEquals(listOf("右手の山は？"), sent)
  }

  @Test
  fun a_pasted_text_is_cut_at_500_characters() {
    show()
    rule.onNodeWithTag("textAskInput").performTextInput("あ".repeat(520))
    assertEquals(500, sheet.text.length)
    rule.onNodeWithTag("textAskLeft").assertTextEquals("残り 0字 · 回答は読み上げます")
  }

  @Test
  fun the_sheet_says_it_cannot_reach_the_network_and_waits_while_sending() {
    sheet = TextSheet(open = true, text = "右手の山は？", message = TEXT_ASK_OFFLINE_MESSAGE)
    show()
    rule.onNodeWithTag("textAskMessage").assertTextEquals("通信できません")
    sheet = sheet.copy(sending = true)
    rule.onNodeWithTag("textAskSend").assertIsNotEnabled()
    rule.onNodeWithText("やめる").performClick()
    assertEquals(1, dismissed)
  }

  // §5.3: taken, the sheet closes and forgets the text; not reached, it keeps the text and says so; refused, it keeps it.
  @Test
  fun what_became_of_the_text_decides_the_sheet() {
    val sending = TextSheet(open = true, text = "右手の山は？", sending = true)
    assertEquals(TextSheet(), sending.after(TextAskOutcome.SENT))
    assertEquals(TextSheet(open = true, text = "右手の山は？", message = "通信できません"), sending.after(TextAskOutcome.OFFLINE))
    assertEquals(TextSheet(open = true, text = "右手の山は？"), sending.copy(message = "通信できません").after(TextAskOutcome.NOT_TAKEN))
  }
}
