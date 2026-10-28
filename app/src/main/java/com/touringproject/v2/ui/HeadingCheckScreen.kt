package com.touringproject.v2.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.settings.HEADING_ACCURACY_M_CHOICES
import com.touringproject.v2.settings.HEADING_HOLD_MINUTES_CHOICES
import com.touringproject.v2.settings.HEADING_REFERENCE_M_CHOICES
import com.touringproject.v2.settings.Settings

private val Muted = Color(0xFF666666)

@Composable
private fun Choices(title: String, note: String, choices: List<Int>, chosen: Int, unit: String, tag: String, onChoose: (Int) -> Unit) {
  Text(title, fontSize = 18.sp)
  Text(note, color = Muted, fontSize = 14.sp)
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    choices.forEach { value ->
      FilterChip(
        selected = chosen == value,
        onClick = { onChoose(value) },
        label = { Text("$value$unit", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        modifier = Modifier.weight(1f).testTag("$tag-$value"),
      )
    }
  }
}

/**
 * SC-22 (slice 5 spec §4.5): a check made while stopped. ui is the heading of the moment (null until the first one is
 * worked out). Each parameter is saved at once and takes effect from the next working-out; onReset puts the three back
 * (「既定値に戻す」, told by a toast).
 */
@Composable
fun HeadingCheckScreen(ui: HeadingCheckUi?, settings: Settings, onUpdate: (Settings) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFCCCCCC))) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ui == null) {
          Text("位置を確かめています…", fontSize = 16.sp)
        } else {
          Text(ui.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("headingTitle"))
          ui.sides?.let { Text(it, color = Muted, fontSize = 16.sp, modifier = Modifier.testTag("headingSides")) }
          ui.reason?.let { Text(it, fontSize = 16.sp, modifier = Modifier.testTag("headingReason")) }
          ui.detail?.let { Text(it, color = Muted, fontSize = 14.sp, modifier = Modifier.testTag("headingDetail")) }
          Text(ui.source, color = Muted, fontSize = 14.sp, modifier = Modifier.testTag("headingSource"))
        }
      }
    }
    Choices("基準の距離", "今の位置からこれだけ離れた点との向きを方位にします", HEADING_REFERENCE_M_CHOICES, settings.headingReferenceMeters, "m", "headingReference") {
      onUpdate(settings.copy(headingReferenceMeters = it))
    }
    Choices("精度の上限", "これより精度の悪い位置は使いません", HEADING_ACCURACY_M_CHOICES, settings.headingAccuracyMeters, "m", "headingAccuracy") {
      onUpdate(settings.copy(headingAccuracyMeters = it))
    }
    Choices("向きを保つ上限", "止まっているあいだ、止まる直前の向きを使い続ける長さ", HEADING_HOLD_MINUTES_CHOICES, settings.headingHoldMinutes, "分", "headingHold") {
      onUpdate(settings.copy(headingHoldMinutes = it))
    }
    Button(onClick = onReset, modifier = Modifier.fillMaxWidth().testTag("headingDefaults")) { Text("既定値に戻す", fontSize = 16.sp) }
  }
}
