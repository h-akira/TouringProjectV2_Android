package com.touringproject.v2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.ask.TextAskOutcome
import com.touringproject.v2.ask.cutTextAsk
import com.touringproject.v2.ask.textAskLeft
import com.touringproject.v2.ask.textQuestion

const val TEXT_ASK_OFFLINE_MESSAGE = "通信できません"

/**
 * Home's text sheet (slice 5 spec §5.2): whether it is open, what is typed, what it says, and whether a send is under
 * way. AppGraph holds it for the process, so the text stays while SC-03 covers the screen and the sheet comes back with
 * it, and an outcome after the activity was recreated still reaches it.
 */
data class TextSheet(val open: Boolean = false, val text: String = "", val message: String? = null, val sending: Boolean = false)

/** What the sheet becomes once the ask loop has told what became of the text (TextAskOutcome). */
fun TextSheet.after(outcome: TextAskOutcome): TextSheet = when (outcome) {
  TextAskOutcome.SENT -> TextSheet()
  TextAskOutcome.OFFLINE -> copy(sending = false, message = TEXT_ASK_OFFLINE_MESSAGE)
  TextAskOutcome.NOT_TAKEN -> copy(sending = false, message = null)
}

/**
 * SC-02's 「⌨ 文字で質問」 sheet (slice 5 spec §5.2): up to 500 characters with what is left, 「質問する」 only for a
 * question that is not just blanks, and 「やめる」.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextAskSheet(sheet: TextSheet, onText: (String) -> Unit, onSend: (String) -> Unit, onDismiss: () -> Unit) {
  val question = textQuestion(sheet.text)
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("文字で質問", fontSize = 20.sp, fontWeight = FontWeight.Bold)
      OutlinedTextField(
        value = sheet.text,
        onValueChange = { onText(cutTextAsk(it)) },
        placeholder = { Text("例: 右手に見える山は何？") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("textAskInput"),
      )
      Text(
        "残り ${textAskLeft(sheet.text)}字 · 回答は読み上げます",
        Modifier.fillMaxWidth().testTag("textAskLeft"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        textAlign = TextAlign.End,
      )
      sheet.message?.let { Text(it, color = Color(0xFFD42A2A), modifier = Modifier.testTag("textAskMessage")) }
      Button(
        onClick = { question?.let(onSend) },
        enabled = question != null && !sheet.sending,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("textAskSend"),
      ) { Text("質問する", fontSize = 18.sp) }
      FilledTonalButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("やめる") }
    }
  }
}
