package com.touringproject.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.touringproject.v2.ride.RIDE_NAME_MAX
import com.touringproject.v2.ride.rideName

/** SC-02's ride card (slice 2 spec §7.2). Memo buttons wait for slice 3; the question count waits for plan 3. */
@Composable
fun RideCard(ride: RideCardUi?, onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onEnd: () -> Unit) {
  val card = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(14.dp)
  if (ride == null) {
    Column(card, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onStart, Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("startRide")) { Text("▶ 走行を開始", fontSize = 18.sp) }
      Text("記録しなくても質問できます（日ごとの記録に残ります）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
    return
  }
  Column(card, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      if (ride.paused) Text("⏸ 一時停止中", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
      else Text("● 走行を記録中", color = Color(0xFFD42A2A), fontWeight = FontWeight.Bold)
      Text(ride.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
      Stat(ride.elapsed, "走行時間", Modifier.testTag("rideElapsed"))
      Stat(ride.distance, "距離", Modifier.testTag("rideDistance"))
    }
    if (ride.paused) Text("一時停止中の質問は走行の記録に入りません（日ごとには残ります）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (ride.paused) Button(onClick = onResume, Modifier.weight(1f)) { Text("▶ 再開") }
      else OutlinedButton(onClick = onPause, Modifier.weight(1f)) { Text("⏸ 一時停止") }
      OutlinedButton(onClick = onEnd, Modifier.weight(1f).testTag("endRide")) { Text("■ 終了") }
    }
  }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
  }
}

@Composable
private fun NameField(name: String, onChange: (String) -> Unit, placeholder: String?) {
  OutlinedTextField(
    value = name,
    onValueChange = { onChange(it.take(RIDE_NAME_MAX)) },
    label = { Text("この走行の名前") },
    placeholder = placeholder?.let { { Text(it) } },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().testTag("rideName"),
  )
}

/** SC-17 (slice 2 spec §7.3). Without the location permission the ride cannot start, and the sheet says why. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartRideSheet(locationAllowed: Boolean, onStart: (String) -> Unit, onDismiss: () -> Unit) {
  var name by remember { mutableStateOf("") }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("走行を開始", fontSize = 20.sp, fontWeight = FontWeight.Bold)
      NameField(name, { name = it }, "例: 箱根・御殿場")
      Text("名前を入れると開始できます。あとで変えることもできます。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
      if (!locationAllowed) {
        Text("位置の許可が無いので、走行を記録できません。端末の設定で、このアプリに位置の使用を許可してください。", color = Color(0xFFD42A2A), modifier = Modifier.testTag("noLocation"))
      }
      Button(
        onClick = { rideName(name)?.let(onStart) },
        enabled = locationAllowed && rideName(name) != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("startGo"),
      ) { Text("▶ 開始", fontSize = 18.sp) }
      FilledTonalButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("やめる") }
    }
  }
}

/** SC-04 (slice 2 spec §7.3). The name may be changed here but not emptied. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndRideSheet(summary: EndSummaryUi, initialName: String, onEnd: (String) -> Unit, onDismiss: () -> Unit) {
  var name by remember { mutableStateOf(initialName) }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("走行を終了しますか？", fontSize = 20.sp, fontWeight = FontWeight.Bold)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        Stat(summary.elapsed, "走行時間")
        Stat(summary.distance, "距離")
      }
      Text(summary.note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
      NameField(name, { name = it }, null)
      Button(onClick = { rideName(name)?.let(onEnd) }, enabled = rideName(name) != null, modifier = Modifier.fillMaxWidth().testTag("endGo")) {
        Text("終了して保存")
      }
      FilledTonalButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("走行を続ける") }
    }
  }
}
