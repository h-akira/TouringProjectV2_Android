package com.touringproject.v2.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight

private val Danger = Color(0xFFD42A2A)

/** DL-01's wording for what SC-06, SC-07 and SC-19 delete (the mock's table). */
enum class DeleteKind(val title: String, val text: String) {
  ASK("この会話を削除しますか？", "質問・回答・メモが削除されます。元に戻せません。"),
  RIDE("この走行を削除しますか？", "ルート・走行のメモと、その走行の質問・地点メモが削除されます。元に戻せません。"),
  NOTE("この地点メモを削除しますか？", "メモが削除されます。元に戻せません。"),
}

/**
 * DL-01, the one confirmation before anything is deleted (recordings, the connection key, an ask, a ride, a point
 * memo). "やめる" is on the left and the red delete on the right (the mock).
 */
@Composable
fun DeleteDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = text?.let { { Text(it) } },
    confirmButton = {
      TextButton(onClick = onConfirm, modifier = Modifier.testTag("deleteGo")) { Text(confirm, color = Danger, fontWeight = FontWeight.Bold) }
    },
    dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("deleteCancel")) { Text("やめる") } },
  )
}

@Composable
fun DeleteDialog(kind: DeleteKind, onConfirm: () -> Unit, onDismiss: () -> Unit) =
  DeleteDialog(kind.title, kind.text, "削除する", onConfirm, onDismiss)
