package com.touringproject.v2.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.voice.Heard
import com.touringproject.v2.voice.VoicePhase
import kotlinx.coroutines.launch

// The mock's --memo and --danger.
private val Memo = Color(0xFF13804A)
private val Danger = Color(0xFFD42A2A)

/** SC-19's voice input as drawn (slice 3 spec §7.3). failure is the reason of the last voice that gave no text. */
data class NoteVoiceUi(val phase: VoicePhase, val failure: String?, val heard: Heard?, val online: Boolean)

/**
 * SC-19 (slice 3 spec §7.3). header says when, where and for which ride the memo is; initial is its text kept now (null
 * for one opened from Home). An empty memo cannot be saved. voice draws the voice input ("🎤 声で入力"; none without
 * it): onVoice starts and stops it, and what it heard is added to the text, then told with onHeard. onDelete is given
 * for a kept memo: "この地点メモを削除" asks first (DL-01), and once the memo is deleted the screen closes, back to where
 * it was opened from.
 */
@Composable
fun NoteScreen(
  header: NoteHeaderUi,
  initial: String?,
  onSave: suspend (String) -> Boolean,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  onDelete: (suspend () -> Boolean)? = null,
  voice: NoteVoiceUi? = null,
  onVoice: () -> Unit = {},
  onHeard: (Int) -> Unit = {},
) {
  var deleting by rememberSaveable { mutableStateOf(false) }
  // From "削除する" until the deletion has returned: nothing else may close the screen meanwhile (Android #57).
  var removing by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  MemoEditor(
    "地点メモ", initial, "気づいたこと、また来たい理由など", { text -> text != null && onSave(text) }, onClose, modifier,
    canSave = { it != null },
    saveColor = Memo,
    header = {
      Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(header.time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.testTag("noteTime"))
        Text(header.where, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("notePlace"))
        Text(header.ride, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.testTag("noteRide"))
      }
    },
    heard = voice?.heard,
    onHeard = onHeard,
    busy = removing,
    footer = {
      if (voice != null) VoiceInputArea(voice, onVoice)
      if (onDelete != null) {
        OutlinedButton(
          onClick = { deleting = true },
          enabled = !removing,
          modifier = Modifier.fillMaxWidth().testTag("deleteNote"),
          border = BorderStroke(1.dp, Danger),
        ) {
          Text("この地点メモを削除", color = Danger, fontWeight = FontWeight.Bold)
        }
      }
    },
  )
  if (deleting && onDelete != null) {
    DeleteDialog(
      DeleteKind.NOTE,
      onConfirm = {
        deleting = false
        removing = true
        scope.launch {
          if (onDelete()) {
            onClose()
          } else {
            removing = false
            Toast.makeText(context, "削除できませんでした", Toast.LENGTH_SHORT).show()
          }
        }
      },
      onDismiss = { deleting = false },
    )
  }
}

@Composable
private fun VoiceInputArea(voice: NoteVoiceUi, onVoice: () -> Unit) {
  val recording = voice.phase == VoicePhase.RECORDING
  FilledTonalButton(
    onClick = onVoice,
    enabled = recording || (voice.phase == VoicePhase.IDLE && voice.online),
    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("voice"),
    colors = if (recording) ButtonDefaults.filledTonalButtonColors(containerColor = Danger, contentColor = Color.White) else ButtonDefaults.filledTonalButtonColors(),
  ) {
    Text(
      when (voice.phase) {
        VoicePhase.IDLE -> "🎤 声で入力"
        VoicePhase.RECORDING -> "■ 止めて文字にする"
        VoicePhase.TRANSCRIBING -> "文字にしています…"
      },
      fontSize = 17.sp,
    )
  }
  voice.failure?.let { Text(it, color = Danger, fontSize = 14.sp, modifier = Modifier.testTag("voiceFailure")) }
  if (voice.online) {
    Text("声は文字にするためだけにサーバーへ送ります。AI には聞きません。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
  } else {
    Text("通信できないため、声では入力できません。文字で書けば保存できます。", color = Danger, fontSize = 13.sp)
  }
}

/** SC-19 for a ride or memo that is not kept: it says so, and ✕ and Back still close it (Android #57). */
@Composable
fun NoteNotFound(text: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
  BackHandler(onBack = onClose)
  Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(onClick = onClose, modifier = Modifier.testTag("notFoundClose")) { Text("✕", fontSize = 22.sp) }
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, modifier = Modifier.testTag("notFound"))
  }
}
