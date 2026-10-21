package com.touringproject.v2.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.records.MEMO_MAX
import com.touringproject.v2.records.cutMemo
import com.touringproject.v2.records.memoText
import kotlinx.coroutines.launch

/**
 * SC-08 (slice 3 spec §7.2). target says what the memo is for (会話 3 · 09:41 箱根町仙石原, 走行のメモ); initial is the
 * memo kept now. onSave gets the cleaned memo, or null to remove it, and says whether it was written: a memo that was
 * not is told and the screen stays, so nothing typed is lost (spec §8). ✕ and Back ask before throwing a change away.
 */
@Composable
fun MemoScreen(target: String, initial: String?, onSave: suspend (String?) -> Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
  var input by rememberSaveable { mutableStateOf(initial ?: "") }
  var discarding by rememberSaveable { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  val changed = memoText(input) != initial
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  val close = { if (changed) discarding = true else onClose() }
  BackHandler(onBack = close)
  Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = close, modifier = Modifier.testTag("memoClose")) { Text("✕", fontSize = 22.sp) }
      Text("メモ", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
      Button(
        onClick = {
          saving = true
          scope.launch {
            if (onSave(memoText(input))) onClose() else Toast.makeText(context, "保存できませんでした", Toast.LENGTH_SHORT).show()
            saving = false
          }
        },
        enabled = !saving,
        modifier = Modifier.testTag("memoSave"),
      ) { Text("保存") }
    }
    Text(target, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.testTag("memoTarget"))
    OutlinedTextField(
      input,
      { input = cutMemo(it) },
      Modifier.fillMaxWidth().weight(1f).focusRequester(focus).testTag("memoInput"),
      placeholder = { Text("感じたこと、また行きたい場所など") },
    )
    Text(
      "${input.codePointCount(0, input.length)} / ${MEMO_MAX}字",
      Modifier.align(Alignment.End).testTag("memoCount"),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = 13.sp,
    )
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
