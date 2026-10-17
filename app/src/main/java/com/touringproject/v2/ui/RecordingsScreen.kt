package com.touringproject.v2.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.ask.RecordingMeta
import com.touringproject.v2.recordings.TrialPhase
import com.touringproject.v2.recordings.TrialState

data class RecordingsUi(
  val trial: TrialState,
  val items: List<RecordingMeta>,
  val playing: String?,
  val askBusy: Boolean,
  val message: String?,
)

private val Blue = Color(0xFF1F4FD1)
private val Red = Color(0xFFD42A2A)
private val Grey = Color(0xFF666666)

// SC-12: the trial recording, and the kept recordings of questions with what was heard (slice 1 spec §5.5).
@Composable
fun RecordingsScreen(
  ui: RecordingsUi,
  onTrial: () -> Unit,
  onPlay: (String) -> Unit,
  onStop: () -> Unit,
  onDeleteAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirming by remember { mutableStateOf(false) }
  val trialActive = ui.trial.phase != TrialPhase.IDLE
  val text = trialText(ui.trial)
  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Button(
      onClick = onTrial,
      enabled = trialActive || (!ui.askBusy && ui.playing == null),
      modifier = Modifier.fillMaxWidth().testTag("trial"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = if (ui.trial.phase == TrialPhase.RECORDING) Red else Blue),
    ) { Text(text.button, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)) }
    Text(text.status, color = Grey, fontSize = 14.sp, modifier = Modifier.testTag("trialStatus"))
    ui.trial.error?.let { Text(it, color = Red, fontSize = 14.sp) }
    Text("送った録音（直近10件）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
      if (ui.items.isEmpty()) item { Text("まだありません", color = Grey, fontSize = 14.sp) }
      items(ui.items, key = { it.name }) { item ->
        val heard = transcriptLabel(item.transcript)
        val isPlaying = ui.playing == item.name
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${recordedAtLabel(item.recordedAt)}　${durationLabel(item.durationMs)}", fontSize = 16.sp)
            Text(micLabel(item.phoneMic, item.device), color = Grey, fontSize = 14.sp)
            Text(heard.text, color = if (heard.failed) Red else Color.Unspecified, fontSize = 15.sp)
          }
          OutlinedButton(
            onClick = { if (isPlaying) onStop() else onPlay(item.name) },
            enabled = isPlaying || !(ui.askBusy || trialActive || ui.playing != null),
            modifier = Modifier.size(56.dp).testTag("play-${item.name}"),
            shape = CircleShape,
          ) { Text(if (isPlaying) "■" else "▶", fontSize = 18.sp) }
        }
        HorizontalDivider()
      }
    }
    ui.message?.let { Text(it, color = Red, fontSize = 14.sp, modifier = Modifier.testTag("recordingsMessage")) }
    OutlinedButton(
      onClick = { confirming = true },
      enabled = !ui.askBusy && !trialActive,
      modifier = Modifier.fillMaxWidth().testTag("deleteAll"),
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, Red),
    ) { Text("録音をすべて消す", color = Red, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
  }
  // DL-01 (recordings)
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      title = { Text("録音をすべて消しますか？") },
      text = { Text("端末に残した質問の音声を消します。質問と回答の記録は消えません。") },
      confirmButton = { TextButton(onClick = { confirming = false; onDeleteAll() }) { Text("消す") } },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text("やめる") } },
    )
  }
}
