package com.touringproject.v2.map

import com.touringproject.v2.ride.RoutePoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapDataTest {
  private fun p(segment: Int, lat: Double, lon: Double) = RoutePoint(segment, 0, lat, lon, 5.0)

  private val points = listOf(p(0, 35.0, 139.0), p(0, 35.1, 139.1), p(2, 35.5, 139.5), p(2, 35.6, 139.6))

  @Test
  fun each_segment_is_a_line_and_a_pause_joins_where_one_stopped_to_where_the_next_started() {
    val map = rideMap(points, ended = true, pins = emptyList())
    assertEquals(listOf(listOf(LatLon(35.0, 139.0), LatLon(35.1, 139.1)), listOf(LatLon(35.5, 139.5), LatLon(35.6, 139.6))), map.routes)
    assertEquals(listOf(listOf(LatLon(35.1, 139.1), LatLon(35.5, 139.5))), map.pauses)
    assertEquals(LatLon(35.0, 139.0), map.start)
    assertEquals(LatLon(35.6, 139.6), map.end)
  }

  @Test
  fun a_ride_not_yet_ended_has_no_end_mark_and_one_without_points_draws_nothing() {
    assertNull(rideMap(points, ended = false, pins = emptyList()).end)
    val empty = rideMap(emptyList(), ended = true, pins = emptyList())
    assertEquals(emptyList<LatLon>(), empty.extent)
    assertNull(empty.start)
  }

  @Test
  fun the_geojson_puts_longitude_first_and_drops_a_line_of_one_point() {
    val map = rideMap(listOf(p(0, 35.0, 139.0), p(1, 35.5, 139.5), p(1, 35.6, 139.6)), ended = true, pins = emptyList())
    val features = Json.parseToJsonElement(routesJson(map)).jsonObject["features"]!!.jsonArray
    assertEquals(1, features.size)
    val first = features[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
    assertEquals(listOf("139.5", "35.5"), first.map { it.jsonPrimitive.content })
  }

  @Test
  fun the_selected_pin_takes_its_own_image() {
    val map = rideMap(points, ended = true, pins = listOf(MapPin("a1", 1, LatLon(35.0, 139.0)), MapPin("a2", 2, LatLon(35.1, 139.1))))
    val features = Json.parseToJsonElement(pinsJson(map, "a2")).jsonObject["features"]!!.jsonArray
    assertEquals(
      listOf("pin-1", "pin-sel-2"),
      features.map { it.jsonObject["properties"]!!.jsonObject["icon"]!!.jsonPrimitive.content },
    )
  }

  @Test
  fun a_map_with_a_focus_opens_on_it_alone() {
    val at = LatLon(35.1, 139.1)
    assertEquals(listOf(at), rideMap(points, ended = true, pins = listOf(MapPin("a1", 1, at)), faint = true, focus = at).extent)
  }
}
