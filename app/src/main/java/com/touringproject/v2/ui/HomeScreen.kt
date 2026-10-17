package com.touringproject.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class HomeUi(val userName: String, val banner: Banner?, val conversation: String)

// SC-02 (slice 1 minimum). Glanced at while riding: it never scrolls.
@Composable
fun HomeScreen(
  ui: HomeUi,
  onTapToTalk: () -> Unit,
  onSettings: () -> Unit,
  onUser: () -> Unit,
  onBanner: (Banner) -> Unit,
  onNewConversation: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirming by remember { mutableStateOf(false) }
  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(ui.userName, Modifier.clickable(onClick = onUser), fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTapToTalk, Modifier.testTag("tapToTalk")) { Text("🎤 押して話す") }
        OutlinedButton(onClick = onSettings) { Text("⚙ 設定") }
      }
    }
    ui.banner?.let { banner ->
      Text(
        banner.text,
        Modifier.fillMaxWidth().background(Color(0xFFF5C518), RoundedCornerShape(8.dp)).clickable { onBanner(banner) }.padding(12.dp).testTag("banner"),
        color = Color.Black,
        fontSize = 16.sp,
      )
    }
    Text("インカムのボタンで話せます", color = Color(0xFF13804A), fontSize = 16.sp)
    Column(
      Modifier.fillMaxWidth().heightIn(min = 76.dp, max = 140.dp).background(Color(0xFF1F4FD1), RoundedCornerShape(16.dp))
        .clickable { confirming = true }.padding(16.dp).testTag("newConversation"),
      verticalArrangement = Arrangement.Center,
    ) {
      Text(ui.conversation, color = Color.White, fontSize = 16.sp)
      Text("新しい会話を始める", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
  }
  // DL-02
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      title = { Text("新しい会話を始めますか？") },
      text = { Text("次の質問から、これまでの会話を踏まえずに答えます。") },
      confirmButton = { TextButton(onClick = { confirming = false; onNewConversation() }) { Text("新しい会話にする") } },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text("やめる") } },
    )
  }
}
