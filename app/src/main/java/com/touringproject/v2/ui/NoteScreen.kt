package com.touringproject.v2.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.launch

// The mock's --memo and --danger.
private val Memo = Color(0xFF13804A)
private val Danger = Color(0xFFD42A2A)

/**
 * SC-19 (slice 3 spec §7.3), with text only: voice input comes with plan 4. header says when, where and for which ride
 * the memo is; initial is its text kept now (null for one opened from Home). An empty memo cannot be saved. onDelete is
 * given for a kept memo: "この地点メモを削除" asks first (DL-01), and once the memo is deleted the screen closes, back to
 * where it was opened from.
 */
@Composable
fun NoteScreen(
  header: NoteHeaderUi,
  initial: String?,
  onSave: suspend (String) -> Boolean,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  onDelete: (suspend () -> Boolean)? = null,
) {
  var deleting by rememberSaveable { mutableStateOf(false) }
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
    footer = {
      if (onDelete != null) {
        OutlinedButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth().testTag("deleteNote"), border = BorderStroke(1.dp, Danger)) {
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
        scope.launch { if (onDelete()) onClose() else Toast.makeText(context, "削除できませんでした", Toast.LENGTH_SHORT).show() }
      },
      onDismiss = { deleting = false },
    )
  }
}
