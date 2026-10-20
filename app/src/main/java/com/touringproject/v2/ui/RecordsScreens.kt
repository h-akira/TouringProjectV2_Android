package com.touringproject.v2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.map.MapData
import com.touringproject.v2.ride.cutRideName
import com.touringproject.v2.ride.rideName

// SC-05, SC-06 and SC-07 (slice 2 spec §7.4–§7.6). The map comes in as a slot, so these screens are tested without
// MapLibre, which needs a real device.

/** Draws data; selected is the askId of the highlighted pin, and onPin gets a tapped pin's askId (null off the pins). */
typealias MapSlot = @Composable (data: MapData, selected: String?, onPin: (String?) -> Unit, modifier: Modifier) -> Unit

private val Accent = Color(0xFFE8600F)
private val Rec = Color(0xFFD42A2A)

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("←", fontSize = 22.sp) }
    Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    actions()
  }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 14) =
  Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = size.sp)

/** SC-05's rides, the one recording or paused first. The switch to the days comes with plan 4b. */
@Composable
fun RideListScreen(sections: List<RideSection>?, onRide: (String) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxSize()) {
    Text("記録", Modifier.padding(16.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
    when {
      sections == null -> Unit
      sections.isEmpty() -> Muted("まだ走行の記録がありません。ホームの「▶ 走行を開始」で記録します。", Modifier.padding(horizontal = 16.dp), 16)
      else -> LazyColumn(Modifier.fillMaxSize().testTag("rideList")) {
        sections.forEach { section ->
          item(key = section.month) { Muted(section.month, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
          items(section.rows, key = { it.id }) { row -> RideRow(row, onRide) }
        }
      }
    }
  }
}

@Composable
private fun RideRow(row: RideRowUi, onRide: (String) -> Unit) {
  Column(Modifier.fillMaxWidth().clickable { onRide(row.id) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("ride-${row.id}")) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(row.name, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        row.state, Modifier.padding(start = 8.dp).testTag("state-${row.id}"), fontSize = 13.sp,
        color = if (row.saved) MaterialTheme.colorScheme.onSurfaceVariant else Accent,
      )
    }
    Muted(row.date)
    Muted(row.stats)
  }
}

/** SC-06 as it is drawn. cards holds the card of each pin that can be tapped. */
data class RideDetailUi(
  val name: String,
  val summary: RideSummaryUi,
  val recording: Boolean,
  val timeline: List<TimelineRow>,
  val map: MapData,
  val cards: Map<String, PinCardUi>,
)

/**
 * SC-06. The map can fill the screen (onFullMap tells AppUi to hide the tabs), its pins can be hidden (showPins is
 * kept in the settings), and a tapped pin shows its card above the map's bottom edge.
 */
@Composable
fun RideDetailScreen(
  ui: RideDetailUi?,
  map: MapSlot,
  showPins: Boolean,
  onShowPins: (Boolean) -> Unit,
  onBack: () -> Unit,
  onAsk: (String) -> Unit,
  onRename: (String) -> Unit,
  onFullMap: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (ui == null) {
    Column(modifier.fillMaxSize()) {
      TopBar("走行", onBack)
      Muted("この走行は見つかりません。", Modifier.padding(16.dp), 16)
    }
    return
  }
  var selected by rememberSaveable { mutableStateOf<String?>(null) }
  var full by rememberSaveable { mutableStateOf(false) }
  var menu by rememberSaveable { mutableStateOf(false) }
  var renaming by rememberSaveable { mutableStateOf<String?>(null) }
  LaunchedEffect(full) { onFullMap(full) }
  DisposableEffect(Unit) { onDispose { onFullMap(false) } }
  BackHandler(enabled = full) { full = false }
  val shown = if (showPins) ui.map else ui.map.copy(pins = emptyList())
  val card = selected?.takeIf { showPins }?.let { ui.cards[it] }
  val mapArea: @Composable (Modifier) -> Unit = { area ->
    Box(area) {
      map(shown, selected.takeIf { showPins }, { selected = it }, Modifier.fillMaxSize())
      TextButton(
        onClick = { full = !full },
        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).testTag("fullMap"),
      ) { Text(if (full) "✕" else "⛶", fontSize = 18.sp) }
      FilterChip(
        selected = showPins,
        onClick = { onShowPins(!showPins) },
        label = { Text("💬 質問") },
        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).testTag("pinToggle"),
      )
      card?.let { PinCard(it, { onAsk(it.askId) }, Modifier.align(Alignment.BottomCenter).padding(8.dp)) }
    }
  }
  // The map keeps one place in the composition and only changes size, so full screen keeps the rider's pan and zoom.
  Column(modifier.fillMaxSize()) {
    if (!full) {
      TopBar(ui.name, onBack) {
        Box {
          TextButton(onClick = { menu = true }, modifier = Modifier.testTag("rideMenu")) { Text("⋮", fontSize = 22.sp) }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("名前を変える") }, onClick = {
              menu = false
              renaming = ui.name
            }, modifier = Modifier.testTag("renameRide"))
          }
        }
      }
    }
    mapArea(if (full) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(280.dp))
    if (!full) {
      LazyColumn(Modifier.fillMaxSize().testTag("timeline")) {
        item { Summary(ui.summary, ui.recording) }
        item { Muted("時系列", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
        if (ui.timeline.isEmpty()) item { Muted("この走行の記録中にした質問はありません。", Modifier.padding(horizontal = 16.dp)) }
        items(ui.timeline) { row -> TimelineItem(row, onAsk) }
      }
    }
  }
  renaming?.let { name ->
    AlertDialog(
      onDismissRequest = { renaming = null },
      title = { Text("名前を変える") },
      text = {
        OutlinedTextField(name, { renaming = cutRideName(it) }, Modifier.fillMaxWidth().testTag("renameInput"), label = { Text("この走行の名前") }, singleLine = true)
      },
      confirmButton = {
        TextButton(
          onClick = { rideName(name)?.let(onRename); renaming = null },
          enabled = rideName(name) != null,
          modifier = Modifier.testTag("renameGo"),
        ) { Text("変える") }
      },
      dismissButton = { TextButton(onClick = { renaming = null }) { Text("やめる") } },
    )
  }
}

@Composable
private fun PinCard(card: PinCardUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).clickable(onClick = onOpen)
      .padding(12.dp).testTag("pinCard"),
  ) {
    Muted(card.heading, size = 13)
    Text(card.question, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    card.answer?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) }
    Text("詳しく見る ›", color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
  }
}

@Composable
private fun Summary(summary: RideSummaryUi, recording: Boolean) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Muted("${summary.date} · ${summary.span}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
      SummaryStat(summary.elapsed, "走行時間")
      SummaryStat(summary.distance, "距離")
      SummaryStat(summary.questions, "質問", Modifier.testTag("summaryQuestions"))
    }
    summary.pauses?.let { Muted(it) }
    if (summary.autoEnded) Text("最長時間に達したため、自動で終了しました", color = Rec, fontSize = 14.sp, modifier = Modifier.testTag("autoEnded"))
    if (recording) Muted("記録中の走行です。ルートは開いたときまでのものです。")
  }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier = Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Muted(label, size = 13)
  }
}

@Composable
private fun TimelineItem(row: TimelineRow, onAsk: (String) -> Unit) {
  when (row) {
    is TimelineRow.Day -> Text(row.label, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.Bold)
    is TimelineRow.Pause -> Muted(row.label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    TimelineRow.NewConversation -> Muted("↻ ここから新しい会話", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), 13)
    is TimelineRow.Ask -> Row(
      Modifier.fillMaxWidth().clickable { onAsk(row.askId) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("ask-${row.askId}"),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(28.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
        Text(row.number.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
      }
      Column(Modifier.weight(1f)) {
        Muted("${row.time} · ${row.place}", size = 13)
        Text(
          row.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
          fontWeight = if (row.failed) FontWeight.Normal else FontWeight.Bold,
          color = if (row.failed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
      }
    }
  }
}

/**
 * SC-07. map is the ask's place on the faint route of its ride (null when the ask has no place). ‹ › move among the
 * ride's asks; the map is made again for each ask, so it opens on that ask.
 */
@Composable
fun AskDetailScreen(detail: AskDetailUi?, map: MapData?, mapSlot: MapSlot, askId: String, onBack: () -> Unit, onMove: (String) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxSize()) {
    TopBar(detail?.title ?: "会話", onBack) {
      TextButton(onClick = { detail?.previous?.let(onMove) }, enabled = detail?.previous != null, modifier = Modifier.testTag("askPrev")) { Text("‹", fontSize = 24.sp) }
      TextButton(onClick = { detail?.next?.let(onMove) }, enabled = detail?.next != null, modifier = Modifier.testTag("askNext")) { Text("›", fontSize = 24.sp) }
    }
    if (detail == null) {
      Muted("この会話は見つかりません。", Modifier.padding(16.dp), 16)
      return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if (map != null) key(askId) { mapSlot(map, askId, {}, Modifier.fillMaxWidth().height(160.dp).testTag("askMap")) }
      else Muted("この質問の場所は分かりません。")
      Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Meta("日時", detail.time)
        Meta("場所", detail.place)
        Meta("進行方向", detail.direction)
        Meta("入力", detail.input)
      }
      Muted("あなた", Modifier.align(Alignment.End), 13)
      Text(
        detail.question ?: "（聞き取れませんでした）",
        Modifier.align(Alignment.End).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(12.dp).testTag("askQuestion"),
      )
      Muted("AI", size = 13)
      if (detail.failure != null) {
        Text("答えられませんでした: ${detail.failure}", color = Rec, modifier = Modifier.testTag("askFailure"))
      } else {
        Text(
          detail.answer ?: "",
          Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp).testTag("askAnswer"),
        )
      }
    }
  }
}

@Composable
private fun Meta(label: String, value: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Muted(label, Modifier.padding(top = 1.dp), 13)
    Text(value, fontSize = 15.sp)
  }
}
