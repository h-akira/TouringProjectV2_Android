package com.touringproject.v2.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.map.LatLon
import com.touringproject.v2.map.MapPin
import com.touringproject.v2.map.RouteMap
import com.touringproject.v2.map.rideMap
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideController
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.settings.Settings
import com.touringproject.v2.store.RoomRecords
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
private fun readRoute(records: RoomRecords, rideId: String): List<RoutePoint>? =
  produceState<List<RoutePoint>?>(null, rideId) { value = records.route(rideId) }.value

fun rideDetailUi(ride: Ride, asks: List<AskRecord>, route: List<RoutePoint>, now: Long, zone: ZoneId) = RideDetailUi(
  name = ride.name,
  summary = rideSummary(ride, asks.size, now, zone),
  recording = ride.status != RideStatus.ENDED,
  timeline = rideTimeline(ride, asks, zone),
  map = rideMap(route, ride.status == RideStatus.ENDED, askPins(asks)),
  cards = asks.mapNotNull { pinCard(asks, it.askId, zone) }.associateBy { it.askId },
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
  modifier: Modifier = Modifier,
) {
  val ride = readRide(records, rideId) ?: return
  val asks = readAsks(records, rideId) ?: return
  val route = readRoute(records, rideId) ?: return
  // A long ride has tens of thousands of points: the map's data is made off the main thread (Android #42).
  val ui = produceState<Read<RideDetailUi?>?>(null, ride, asks, route) {
    value = Read(withContext(Dispatchers.Default) { ride.value?.let { rideDetailUi(it, asks, route, System.currentTimeMillis(), ZoneId.systemDefault()) } })
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
fun AskDetailRoute(records: RoomRecords, rideId: String, askId: String, onBack: () -> Unit, onMove: (String) -> Unit, modifier: Modifier = Modifier) {
  val ride = readRide(records, rideId) ?: return
  val asks = readAsks(records, rideId) ?: return
  val route = readRoute(records, rideId) ?: return
  val zone = ZoneId.systemDefault()
  val detail = remember(asks, askId) { askDetail(asks, askId, zone) }
  val map = remember(asks, route, askId, ride) { askMap(asks.firstOrNull { it.askId == askId }, asks, route, ride.value?.status == RideStatus.ENDED) }
  AskDetailScreen(detail, map, routeMap, askId, onBack, onMove, modifier)
}
