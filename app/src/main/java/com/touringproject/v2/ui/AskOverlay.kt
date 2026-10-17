package com.touringproject.v2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.Phase
import com.touringproject.v2.ask.RECORD_LIMIT_MS
import com.touringproject.v2.ask.spokenMessage
import kotlinx.coroutines.delay

data class OverlayText(val title: String, val big: String, val detail: String, val button: String)

fun overlayText(state: FlowState, seconds: Long): OverlayText = when (state.phase) {
  Phase.RECORDING -> OverlayText("話してください", (RECORD_LIMIT_MS / 1000 - seconds).coerceAtLeast(0).toString(), "もう一度ボタンを押すか、ここを押すと送ります", "送らずにやめる")
  Phase.SENDING -> OverlayText("送っています…", "", "", "送らずにやめる")
  Phase.WAITING -> OverlayText("考えています…", seconds.toString(), "最初の質問は10秒ほど", "待つのをやめる")
  Phase.SPEAKING -> OverlayText("回答中", "", state.answer ?: "", "読み上げを止める")
  Phase.FAILED -> OverlayText(state.failure?.let(::spokenMessage) ?: "失敗しました", "", "", "閉じる")
  Phase.IDLE -> OverlayText("", "", "", "")
}

// SC-03: covers the whole screen in the phase's colour while an ask runs.
@Composable
fun AskOverlay(state: FlowState, now: () -> Long, onStopRecording: () -> Unit, onCancel: () -> Unit) {
  if (state.phase == Phase.IDLE) return
  var clock by remember { mutableLongStateOf(now()) }
  // Display-only timer; the ask loop does not depend on it.
  LaunchedEffect(state.phaseStartedAt) {
    while (true) {
      clock = now()
      delay(250)
    }
  }
  // The overlay is not a screen of its own: Back must not reveal the screen under it mid-ask.
  BackHandler {}
  val seconds = ((clock - state.phaseStartedAt) / 1000).coerceAtLeast(0)
  val text = overlayText(state, seconds)
  val onMain: () -> Unit = when (state.phase) {
    Phase.RECORDING -> onStopRecording
    Phase.SPEAKING -> onCancel
    else -> ({})
  }
  Column(
    Modifier.fillMaxSize().background(phaseColor(state.phase)).safeDrawingPadding().padding(24.dp).testTag("overlay"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (state.phoneMic && state.phase == Phase.RECORDING) {
      Text(
        "インカムのマイクに繋がっていません（本体のマイクで録音中）",
        Modifier.fillMaxWidth().background(Color(0xFFF5C518), RoundedCornerShape(8.dp)).padding(12.dp),
        color = Color.Black,
        fontSize = 18.sp,
      )
    }
    Column(
      Modifier.weight(1f).fillMaxWidth().clickable(onClick = onMain).testTag("overlayMain"),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(text.title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
      if (text.big.isNotEmpty()) Text(text.big, color = Color.White, fontSize = 140.sp, fontWeight = FontWeight.ExtraBold)
      if (text.detail.isNotEmpty()) Text(text.detail, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center, maxLines = 8)
    }
    OutlinedButton(
      onClick = onCancel,
      modifier = Modifier.fillMaxWidth().testTag("overlayCancel"),
      border = BorderStroke(4.dp, Color.White),
      shape = RoundedCornerShape(16.dp),
    ) {
      Text(text.button, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
    }
  }
}
