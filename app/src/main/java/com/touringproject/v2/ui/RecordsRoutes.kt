package com.touringproject.v2.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.map.LatLon
import com.touringproject.v2.map.MapPin
import com.touringproject.v2.map.RouteMap
import com.touringproject.v2.map.rideMap
import com.touringproject.v2.records.AskEdits
import com.touringproject.v2.records.NoteDraft
import com.touringproject.v2.records.NoteEdits
import com.touringproject.v2.records.NoteRecord
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideController
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.settings.Settings
import com.touringproject.v2.store.RoomRecords
import com.touringproject.v2.ask.Source
import com.touringproject.v2.voice.VoiceInput
import com.touringproject.v2.voice.VoiceState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId

// Reads what SC-06 and SC-07 show from the device's records and hands it to the screens. Nothing is drawn until the
// ride, its asks and its route have all been read, so a screen never flashes "not found" while loading.

/** MapLibre, for the app. The screen tests pass a stand-in. */
val routeMap: MapSlot = { data, selected, onPin, modifier -> RouteMap(data, selected, onPin, modifier) }

/** A value that has been read, which may be null (a ride that is not kept); null itself means "not read yet". */
private data class Read<T>(val value: T)

@Composable
private fun readRide(records: RoomRecords, rideId: String): Read<Ride?>? =
  remember(rideId) { records.ride(rideId).map { Read(it) } }.collectAsStateWithLifecycle(null).value

@Composable
private fun readAsks(records: RoomRecords, rideId: String): List<AskRecord>? =
  remember(rideId) { records.asks(rideId) }.collectAsStateWithLifecycle(null).value

@Composable
private fun readNotes(records: RoomRecords, rideId: String): List<NoteRecord>? =
  remember(rideId) { records.notes(rideId) }.collectAsStateWithLifecycle(null).value

@Composable
private fun readRoute(records: RoomRecords, rideId: String): List<RoutePoint>? =
  produceState<List<RoutePoint>?>(null, rideId) { value = records.route(rideId) }.value

/** The asks' and the point memos' cards share one map: their ids are UUIDs, so they never meet. */
fun rideDetailUi(ride: Ride, asks: List<AskRecord>, notes: List<NoteRecord>, route: List<RoutePoint>, now: Long, zone: ZoneId) = RideDetailUi(
  name = ride.name,
  summary = rideSummary(ride, asks.size, now, zone, notes.size),
  recording = ride.status != RideStatus.ENDED,
  timeline = rideTimeline(ride, asks, zone, notes),
  map = rideMap(route, ride.status == RideStatus.ENDED, askPins(asks) + notePins(notes)),
  cards = (asks.mapNotNull { pinCard(asks, it.askId, zone) } + notes.mapNotNull { noteCard(notes, it.id, zone) }).associateBy { it.id },
  memo = ride.memo,
)

@Composable
fun RideDetailRoute(
  records: RoomRecords,
  rides: RideController,
  settings: Settings,
  updateSettings: (Settings) -> Unit,
  rideId: String,
  onBack: () -> Unit,
  onAsk: (String) -> Unit,
  onFullMap: (Boolean) -> Unit,
  onMemo: () -> Unit,
  modifier: Modifier = Modifier,
  onNote: (String) -> Unit = {},
) {
  val ride = readRide(records, rideId) ?: return
  val asks = readAsks(records, rideId) ?: return
  val notes = readNotes(records, rideId) ?: return
  val route = readRoute(records, rideId) ?: return
  // A long ride has tens of thousands of points: the map's data is made off the main thread (Android #42).
  val ui = produceState<Read<RideDetailUi?>?>(null, ride, asks, notes, route) {
    value = Read(withContext(Dispatchers.Default) { ride.value?.let { rideDetailUi(it, asks, notes, route, System.currentTimeMillis(), ZoneId.systemDefault()) } })
  }.value ?: return
  RideDetailScreen(
    ui.value, routeMap,
    showPins = settings.showAskPins,
    onShowPins = { updateSettings(settings.copy(showAskPins = it)) },
    onBack = onBack,
    onAsk = onAsk,
    onRename = { name -> rides.rename(rideId, name) },
    onFullMap = onFullMap,
    modifier = modifier,
    onMemo = onMemo,
    onDelete = { rides.delete(rideId).also { if (it) onBack() } },
    onNote = onNote,
    showNotes = settings.showNotePins,
    onShowNotes = { updateSettings(settings.copy(showNotePins = it)) },
  )
}

/** SC-07's map: the ask's numbered pin on its ride's faint route, opened close up on the pin. */
fun askMap(ask: AskRecord?, asks: List<AskRecord>, route: List<RoutePoint>, ended: Boolean) = ask?.let { a ->
  val lat = a.latitude ?: return@let null
  val lon = a.longitude ?: return@let null
  val at = LatLon(lat, lon)
  rideMap(route, ended, listOf(MapPin(a.askId, askNumbers(asks).getValue(a.askId), at)), faint = true, focus = at)
}

@Composable
fun AskDetailRoute(
  records: RoomRecords,
  edits: AskEdits,
  rideId: String,
  askId: String,
  onBack: () -> Unit,
  onMove: (String) -> Unit,
  onMemo: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val ride = readRide(records, rideId) ?: return
  val asks = readAsks(records, rideId) ?: return
  val route = readRoute(records, rideId) ?: return
  val zone = ZoneId.systemDefault()
  val detail = remember(asks, askId) { askDetail(asks, askId, zone) }
  val map = remember(asks, route, askId, ride) { askMap(asks.firstOrNull { it.askId == askId }, asks, route, ride.value?.status == RideStatus.ENDED) }
  AskDetailScreen(detail, map, routeMap, askId, onBack, onMove, modifier, onMemo, onDelete = { edits.delete(askId).also { if (it) onBack() } })
}

/** SC-08 for a ride's memo, from SC-02 or SC-06. Nothing is drawn until the ride has been read, nor for a ride not kept. */
@Composable
fun RideMemoRoute(records: RoomRecords, rides: RideController, rideId: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val ride = readRide(records, rideId) ?: return
  val kept = ride.value ?: return
  MemoScreen("走行のメモ · ${kept.name}", kept.memo, { rides.memo(rideId, it) }, onClose, modifier)
}

/** SC-08 for an ask's memo, from SC-07. */
@Composable
fun AskMemoRoute(records: RoomRecords, edits: AskEdits, rideId: String, askId: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val asks = readAsks(records, rideId) ?: return
  val ask = asks.firstOrNull { it.askId == askId } ?: return
  val target = remember(asks, askId) { askMemoTarget(asks, askId, ZoneId.systemDefault()) } ?: return
  MemoScreen(target, ask.memo, { edits.memo(askId, it) }, onClose, modifier)
}

/** SC-19's voice input as the routes take it from AppGraph. */
class NoteVoice(val input: VoiceInput, val state: StateFlow<VoiceState>, val online: StateFlow<Boolean>)

/**
 * The voice input while SC-19 is in front: attached, the intercom button goes to it (slice 3 spec §7.3). Going to the
 * back (home, app switch, screen off) or leaving SC-19 detaches it and drops whatever it was doing, so a ride's
 * intercom press while the navigation app is in front starts an ask (review R3-01). The pause, not the stop, is the
 * edge: an intercom press arrives in onNewIntent, which an onResume always follows.
 */
@Composable
private fun voiceUi(voice: NoteVoice?): NoteVoiceUi? {
  voice ?: return null
  LifecycleResumeEffect(voice) {
    val token = voice.input.attach()
    onPauseOrDispose { voice.input.detach(token) }
  }
  val state by voice.state.collectAsStateWithLifecycle()
  val online by voice.online.collectAsStateWithLifecycle()
  return NoteVoiceUi(state.phase, state.failure?.message, state.heard, online)
}

/** SC-19 opened from Home: the memo is kept for the ride it was opened in when it is first saved (slice 3 spec §7.3). */
@Composable
fun NewNoteRoute(records: RoomRecords, notes: NoteEdits, draft: NoteDraft, onClose: () -> Unit, modifier: Modifier = Modifier, voice: NoteVoice? = null) {
  val ride = readRide(records, draft.rideId) ?: return
  val kept = ride.value ?: return NoteNotFound("この走行は見つかりません。", onClose, modifier)
  val header = remember(draft, kept.name) { newNoteHeader(draft, kept.name, ZoneId.systemDefault()) }
  NoteScreen(
    header, null, { notes.create(draft, it) }, onClose, modifier,
    voice = voiceUi(voice), onVoice = { voice?.input?.press(Source.SCREEN) }, onHeard = { voice?.input?.taken(it) },
  )
}

/**
 * SC-19 opened from SC-06, to change or delete a kept memo. The memo last read stays on screen once it is deleted, so
 * the deletion in progress is not cancelled with the screen and closes it once.
 */
@Composable
fun NoteRoute(
  records: RoomRecords,
  notes: NoteEdits,
  rideId: String,
  noteId: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  voice: NoteVoice? = null,
) {
  val ride = readRide(records, rideId) ?: return
  val list = readNotes(records, rideId) ?: return
  var shown by remember(noteId) { mutableStateOf(list.firstOrNull { it.id == noteId }) }
  list.firstOrNull { it.id == noteId }?.let { shown = it }
  val note = shown ?: return NoteNotFound("この地点メモは見つかりません。", onClose, modifier)
  val name = ride.value?.name ?: return NoteNotFound("この走行は見つかりません。", onClose, modifier)
  val header = remember(note, name) { noteHeader(note, name, ZoneId.systemDefault()) }
  NoteScreen(
    header, note.text, { notes.text(noteId, it) }, onClose, modifier, onDelete = { notes.delete(noteId) },
    voice = voiceUi(voice), onVoice = { voice?.input?.press(Source.SCREEN) }, onHeard = { voice?.input?.taken(it) },
  )
}
