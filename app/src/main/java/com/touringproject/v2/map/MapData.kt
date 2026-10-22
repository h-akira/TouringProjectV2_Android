package com.touringproject.v2.map

import com.touringproject.v2.ride.RoutePoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What a map draws, worked out without MapLibre so it is tested on the JVM (slice 2 spec §7.7).

data class LatLon(val lat: Double, val lon: Double)

/** Asks are orange pins, point memos green ones, each numbered on its own (US-D-08). */
enum class PinKind { ASK, NOTE }

/**
 * A numbered ask or point memo on the map; id is its askId or noteId, and number its place among the ride's of its
 * kind. memo: the ask has a memo (US-D-04.005).
 */
data class MapPin(val id: String, val number: Int, val at: LatLon, val memo: Boolean = false, val kind: PinKind = PinKind.ASK)

/**
 * routes has one line per recorded segment that has points; pauses joins the last point of a segment to the first
 * of the next one (slice 2 spec §7.5). end is set only once the ride has ended. faint draws the route lightly, under
 * one ask's pin, and focus is where the camera opens instead of the whole route (SC-07).
 */
data class MapData(
  val routes: List<List<LatLon>>,
  val pauses: List<List<LatLon>>,
  val start: LatLon?,
  val end: LatLon?,
  val pins: List<MapPin>,
  val faint: Boolean = false,
  val focus: LatLon? = null,
) {
  /** Everything the camera should take in when the map opens. */
  val extent: List<LatLon> get() = focus?.let { listOf(it) } ?: (routes.flatten() + pins.map { it.at })

  /** SC-06's switches: the asks' and the point memos' pins shown or hidden apart; with both hidden only the route is left. */
  fun showing(asks: Boolean, notes: Boolean): MapData =
    copy(pins = pins.filter { if (it.kind == PinKind.NOTE) notes else asks })
}

fun rideMap(points: List<RoutePoint>, ended: Boolean, pins: List<MapPin>, faint: Boolean = false, focus: LatLon? = null): MapData {
  val routes = points.groupBy { it.segment }.toSortedMap().values.map { segment -> segment.map { LatLon(it.lat, it.lon) } }
  val pauses = routes.zipWithNext { a, b -> listOf(a.last(), b.first()) }
  return MapData(
    routes = routes,
    pauses = pauses,
    start = routes.firstOrNull()?.first(),
    end = if (ended) routes.lastOrNull()?.last() else null,
    pins = pins,
    faint = faint,
    focus = focus,
  )
}

private fun LatLon.coordinates() = buildJsonArray {
  add(JsonPrimitive(lon))
  add(JsonPrimitive(lat))
}

private fun feature(geometry: JsonObject, properties: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
  put("type", "Feature")
  put("geometry", geometry)
  put("properties", properties)
}

private fun collection(features: List<JsonObject>) = buildJsonObject {
  put("type", "FeatureCollection")
  put("features", JsonArray(features))
}.toString()

private fun line(points: List<LatLon>) = buildJsonObject {
  put("type", "LineString")
  put("coordinates", JsonArray(points.map { it.coordinates() }))
}

private fun point(at: LatLon) = buildJsonObject {
  put("type", "Point")
  put("coordinates", at.coordinates())
}

/** A line of a single point draws nothing, so only lines of two or more go. */
fun routesJson(data: MapData): String = collection(data.routes.filter { it.size > 1 }.map { feature(line(it)) })

fun pausesJson(data: MapData): String = collection(data.pauses.map { feature(line(it)) })

/** kind is "start" or "end". */
fun endsJson(data: MapData): String = collection(
  listOfNotNull(data.start?.let { "start" to it }, data.end?.let { "end" to it }).map { (kind, at) ->
    feature(point(at), buildJsonObject { put("kind", kind) })
  },
)

/** id is what a tap on the pin names; icon names the pin's image: its kind, its number, and whether it is the selected one. */
fun pinsJson(data: MapData, selected: String?): String = collection(
  data.pins.map { pin ->
    feature(
      point(pin.at),
      buildJsonObject {
        put("id", pin.id)
        put("icon", pinIcon(pin.number, pin.id == selected, pin.memo, pin.kind))
      },
    )
  },
)

fun pinIcon(number: Int, selected: Boolean, memo: Boolean = false, kind: PinKind = PinKind.ASK): String {
  val name = if (kind == PinKind.NOTE) "note" else "pin"
  return (if (selected) "$name-sel-$number" else "$name-$number") + if (memo) "-memo" else ""
}
