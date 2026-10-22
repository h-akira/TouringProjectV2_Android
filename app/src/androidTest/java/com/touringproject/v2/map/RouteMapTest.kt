package com.touringproject.v2.map

import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.touringproject.v2.ride.RoutePoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

// On the emulator: MapLibre draws the route, the numbered asks and the point memos, and a tap on a pin names its ask or
// memo (slice 2 spec §9, slice 3 spec §7.6). The tiles need not load: the layers are drawn without them.
@RunWith(AndroidJUnit4::class)
class RouteMapTest {
  @get:Rule val rule = createComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  private fun <T> onMain(block: () -> T): T {
    var result: T? = null
    instrumentation.runOnMainSync { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private val points = listOf(
    RoutePoint(0, 0, 35.20, 139.00, 5.0), RoutePoint(0, 0, 35.22, 139.03, 5.0),
    RoutePoint(1, 0, 35.25, 139.06, 5.0), RoutePoint(1, 0, 35.28, 139.10, 5.0),
  )
  private val pins = listOf(
    MapPin("a1", 1, LatLon(35.21, 139.01)), MapPin("a2", 2, LatLon(35.27, 139.09)), MapPin("n1", 1, LatLon(35.235, 139.045), kind = PinKind.NOTE),
  )

  @Test
  fun the_route_and_the_pins_are_drawn_and_a_tap_on_a_pin_names_its_ask_or_memo() {
    var map: MapLibreMap? = null
    val tapped = mutableListOf<String?>()
    rule.setContent { RouteMap(rideMap(points, ended = true, pins = pins), null, { tapped += it }, Modifier.fillMaxSize(), onMap = { map = it }) }
    rule.waitUntil(30_000) { map != null }
    val everywhere = onMain { RectF(0f, 0f, map!!.width, map!!.height) }
    rule.waitUntil(30_000) { onMain { map!!.queryRenderedFeatures(everywhere, PINS_LAYER).size } == 3 }
    check(onMain { map!!.queryRenderedFeatures(everywhere, "routes").isNotEmpty() }) { "the route is not drawn" }
    check(onMain { map!!.queryRenderedFeatures(everywhere, "pauses").isNotEmpty() }) { "the pause is not drawn" }
    // The pin stands on its place: tap its head, above the tip.
    val tip = onMain { map!!.projection.toScreenLocation(LatLng(35.27, 139.09)) }
    val density = instrumentation.targetContext.resources.displayMetrics.density
    rule.onRoot().performTouchInput { click(Offset(tip.x, tip.y - 16 * density)) }
    rule.waitUntil(10_000) { tapped.isNotEmpty() }
    check(tapped.last() == "a2") { "tapped ${tapped.last()}" }
    val memo = onMain { map!!.projection.toScreenLocation(LatLng(35.235, 139.045)) }
    rule.onRoot().performTouchInput { click(Offset(memo.x, memo.y - 16 * density)) }
    rule.waitUntil(10_000) { tapped.last() == "n1" }
  }

  // Final review: a tap while the map is still being drawn must not ask for rendered features (MapLibre would crash).
  @Test
  fun taps_while_the_map_is_still_drawing_do_not_crash() {
    var map: MapLibreMap? = null
    rule.setContent { RouteMap(rideMap(points, ended = true, pins = pins), null, {}, Modifier.fillMaxSize(), onMap = { map = it }) }
    val until = System.currentTimeMillis() + 3_000
    while (map == null && System.currentTimeMillis() < until) {
      rule.onRoot().performTouchInput { click(center) }
    }
    rule.waitUntil(30_000) { map != null }
  }
}
