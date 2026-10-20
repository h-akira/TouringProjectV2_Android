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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.touringproject.v2.audio.MediaVolume
import kotlinx.coroutines.delay

/** askHint is set while no ask can start (no ride recording: slice 2 spec §4.7); it says how to make one possible. */
data class HomeUi(val userName: String, val banner: Banner?, val conversation: String, val askHint: String? = null)

const val VOLUME_CHECK_MS = 2_000L

/**
 * The media volume, read every 2 s while the caller is on screen and the app is in front (slice 1 spec §5.6).
 * Reading stops while an ask holds the intercom route; a withheld reading keeps the last good one.
 */
@Composable
fun rememberMediaVolume(readable: Boolean, read: () -> MediaVolume?): MediaVolume? {
  var volume by remember { mutableStateOf<MediaVolume?>(null) }
  val currentRead by rememberUpdatedState(read)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(readable, lifecycle) {
    if (!readable) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        volume = nextVolume(volume, runCatching { currentRead() }.getOrNull())
        delay(VOLUME_CHECK_MS)
      }
    }
  }
  return volume
}

/** The yellow warning band. The low-volume one leads nowhere and has a ✕ instead. */
@Composable
fun WarningBand(text: String, onClick: (() -> Unit)?, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().background(Color(0xFFF5C518), RoundedCornerShape(8.dp)), verticalAlignment = Alignment.CenterVertically) {
    Text(
      text,
      Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(12.dp),
      color = Color.Black,
      fontSize = 16.sp,
    )
    if (onClose != null) {
      Text("✕", Modifier.clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 12.dp).testTag("bandClose"), color = Color.Black, fontSize = 16.sp)
    }
  }
}

// SC-02 (slice 1 minimum). Glanced at while riding: it never scrolls.
@Composable
fun HomeScreen(
  ui: HomeUi,
  onTapToTalk: () -> Unit,
  onUser: () -> Unit,
  onBanner: (Banner) -> Unit,
  onNewConversation: () -> Unit,
  modifier: Modifier = Modifier,
  onDismissVolume: () -> Unit = {},
  ride: @Composable () -> Unit = {},
) {
  var confirming by remember { mutableStateOf(false) }
  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(ui.userName, Modifier.clickable(onClick = onUser), fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
      // Settings is a tab now (slice 2 spec §7.1).
      OutlinedButton(onClick = onTapToTalk, Modifier.testTag("tapToTalk"), enabled = ui.askHint == null) { Text("🎤 押して話す") }
    }
    ui.banner?.let { banner ->
      val low = banner == Banner.LOW_VOLUME
      WarningBand(banner.text, if (banner.leads) ({ onBanner(banner) }) else null, if (low) onDismissVolume else null, Modifier.testTag("banner"))
    }
    ride()
    Text(
      ui.askHint ?: "インカムのボタンで話せます",
      Modifier.testTag("askHint"),
      color = if (ui.askHint == null) Color(0xFF13804A) else MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = 16.sp,
    )
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
