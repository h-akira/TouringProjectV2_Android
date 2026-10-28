package com.touringproject.v2.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.records.MEMO_MAX
import com.touringproject.v2.records.appendHeard
import com.touringproject.v2.records.cutMemo
import com.touringproject.v2.records.memoText
import com.touringproject.v2.voice.Heard
import kotlinx.coroutines.launch

/**
 * What SC-08 and SC-19 share: ✕, the title and 保存 on top, header under them, the field with its count, and footer
 * below. initial is the text kept now; onSave gets the cleaned text (memoText: null when blank) and says whether it was
 * written: a text that was not is told and the screen stays, so nothing typed is lost (slice 3 spec §8). canSave says
 * whether a cleaned text may be saved. ✕ and Back ask before throwing a change away. While a save runs, 保存, ✕ and
 * Back take nothing, so the screen is closed once (Android #55). The field opens with the cursor at the end. heard is
 * what SC-19's voice input heard: it is added at the end of the text once, and onHeard tells it was. busy is set by
 * the screen while it does something of its own (SC-19 deleting the memo): 保存, ✕ and Back then take nothing either.
 */
@Composable
fun MemoEditor(
  title: String,
  initial: String?,
  placeholder: String,
  onSave: suspend (String?) -> Boolean,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  canSave: (String?) -> Boolean = { true },
  saveColor: Color? = null,
  header: @Composable () -> Unit = {},
  footer: @Composable ColumnScope.() -> Unit = {},
  heard: Heard? = null,
  onHeard: (Int) -> Unit = {},
  busy: Boolean = false,
) {
  var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
    mutableStateOf((initial ?: "").let { TextFieldValue(it, TextRange(it.length)) })
  }
  var discarding by rememberSaveable { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  val clean = memoText(field.text)
  val changed = clean != initial
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  // What was heard did not all fit; told until the text is changed (Android #61).
  var heardCut by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(heard?.seq) {
    val next = heard ?: return@LaunchedEffect
    val appended = appendHeard(field.text, next.text)
    field = TextFieldValue(appended.text, TextRange(appended.text.length))
    heardCut = !appended.whole
    onHeard(next.seq)
  }
  val close = {
    if (!saving && !busy) {
      if (changed) discarding = true else onClose()
    }
  }
  BackHandler(onBack = close)
  Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = close, modifier = Modifier.testTag("memoClose")) { Text("✕", fontSize = 22.sp) }
      Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
      Button(
        onClick = {
          // Two presses in one frame both get here before the button is redrawn as disabled (Android #55).
          if (saving) return@Button
          saving = true
          scope.launch {
            if (onSave(clean)) onClose() else Toast.makeText(context, "保存できませんでした", Toast.LENGTH_SHORT).show()
            saving = false
          }
        },
        enabled = !saving && !busy && canSave(clean),
        colors = saveColor?.let { ButtonDefaults.buttonColors(containerColor = it, contentColor = Color.White) } ?: ButtonDefaults.buttonColors(),
        modifier = Modifier.testTag("memoSave"),
      ) { Text("保存") }
    }
    header()
    OutlinedTextField(
      field,
      {
        if (it.text != field.text) heardCut = false
        field = it.copy(text = cutMemo(it.text))
      },
      Modifier.fillMaxWidth().weight(1f).focusRequester(focus).testTag("memoInput"),
      placeholder = { Text(placeholder) },
    )
    Text(
      "${field.text.codePointCount(0, field.text.length)} / ${MEMO_MAX}字",
      Modifier.align(Alignment.End).testTag("memoCount"),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = 13.sp,
    )
    if (heardCut) Text("入りきらなかった分は足せませんでした", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.testTag("heardCut"))
    footer()
  }
  if (discarding) {
    AlertDialog(
      onDismissRequest = { discarding = false },
      title = { Text("変更を破棄しますか？") },
      confirmButton = {
        TextButton(onClick = { discarding = false; onClose() }, modifier = Modifier.testTag("memoDiscard")) { Text("破棄する") }
      },
      dismissButton = { TextButton(onClick = { discarding = false }) { Text("書き続ける") } },
    )
  }
}

/**
 * SC-08 (slice 3 spec §7.2). target says what the memo is for (会話 3 · 09:41 箱根町仙石原, 走行のメモ); initial is the
 * memo kept now. onSave gets the cleaned memo, or null to remove it (saving an empty memo removes it).
 */
@Composable
fun MemoScreen(target: String, initial: String?, onSave: suspend (String?) -> Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) =
  MemoEditor("メモ", initial, "感じたこと、また行きたい場所など", onSave, onClose, modifier, header = {
    Text(target, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.testTag("memoTarget"))
  })
