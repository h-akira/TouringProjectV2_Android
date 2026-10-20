package com.touringproject.v2.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

// The map of SC-06 and SC-07: GSI's pale tiles with the route, the pauses, the ends and the numbered asks drawn from
// GeoJSON (slice 2 spec §7.7). MapLibre's MapView is wrapped in an AndroidView and follows the screen's lifecycle.

const val GSI_TILES_URL = "https://maps.gsi.go.jp/development/ichiran.html"
private const val STYLE_ASSET = "map/gsi-pale.json"
private const val ROUTE_COLOR = "#F06D1A"
private const val PAUSE_COLOR = "#8A929C"
private const val PIN_COLOR = 0xFFE8600F.toInt()
private const val PIN_SELECTED_COLOR = 0xFF1F4FD1.toInt()
/** The layer a tap on a pin is looked up in (slice 2 spec §7.7). */
const val PINS_LAYER = "pins"
// Japan as a whole, for a ride that has nothing to show yet.
private val JAPAN = LatLng(36.2, 138.25)

private fun styleJson(context: Context) = context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }

/** A teardrop pin with its number, the tip at the bottom, as in the mock. */
fun pinBitmap(number: Int, selected: Boolean, density: Float): Bitmap {
  val radius = (if (selected) 16f else 13f) * density
  val stroke = 2f * density
  val tail = radius * 0.8f
  val width = (2 * (radius + stroke)).toInt()
  val height = (2 * (radius + stroke) + tail).toInt()
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val cx = width / 2f
  val cy = radius + stroke
  val shape = Path().apply {
    addCircle(cx, cy, radius, Path.Direction.CW)
    moveTo(cx - radius * 0.6f, cy + radius * 0.7f)
    lineTo(cx, height - stroke)
    lineTo(cx + radius * 0.6f, cy + radius * 0.7f)
    close()
  }
  canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) PIN_SELECTED_COLOR else PIN_COLOR })
  canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = stroke })
  val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    textSize = 13f * density
    typeface = Typeface.DEFAULT_BOLD
    textAlign = Paint.Align.CENTER
  }
  canvas.drawText(number.toString(), cx, cy - (text.descent() + text.ascent()) / 2, text)
  return bitmap
}

private fun addLayers(style: Style, faint: Boolean, dark: Boolean) {
  // Dark mode dims the tiles instead of turning them over, so the colours of the land stay readable (slice 2 spec §7.7).
  if (dark) (style.getLayer("gsi") as? RasterLayer)?.setProperties(PropertyFactory.rasterBrightnessMax(0.55f), PropertyFactory.rasterSaturation(-0.4f))
  listOf("routes", "pauses", "ends", PINS_LAYER).forEach { style.addSource(GeoJsonSource(it)) }
  style.addLayer(
    LineLayer("pauses", "pauses").withProperties(
      PropertyFactory.lineColor(PAUSE_COLOR),
      PropertyFactory.lineWidth(3f),
      PropertyFactory.lineDasharray(arrayOf(1.3f, 2.3f)),
    ),
  )
  style.addLayer(
    LineLayer("routes", "routes").withProperties(
      PropertyFactory.lineColor(ROUTE_COLOR),
      PropertyFactory.lineWidth(if (faint) 4f else 5f),
      PropertyFactory.lineOpacity(if (faint) 0.45f else 0.92f),
      PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
      PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ),
  )
  style.addLayer(
    CircleLayer("ends", "ends").withProperties(
      PropertyFactory.circleRadius(7f),
      PropertyFactory.circleColor(Expression.match(Expression.get("kind"), Expression.literal("start"), Expression.color(0xFF13804A.toInt()), Expression.color(0xFF15181D.toInt()))),
      PropertyFactory.circleStrokeColor("#FFFFFF"),
      PropertyFactory.circleStrokeWidth(3f),
    ),
  )
  style.addLayer(
    SymbolLayer(PINS_LAYER, PINS_LAYER).withProperties(
      PropertyFactory.iconImage(Expression.get("icon")),
      PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
      PropertyFactory.iconAllowOverlap(true),
      PropertyFactory.iconIgnorePlacement(true),
    ),
  )
}

private fun draw(style: Style, data: MapData, selected: String?, density: Float) {
  data.pins.forEach { pin ->
    listOf(false, true).forEach { sel ->
      val name = pinIcon(pin.number, sel)
      if (style.getImage(name) == null) style.addImage(name, pinBitmap(pin.number, sel, density))
    }
  }
  style.getSourceAs<GeoJsonSource>("routes")?.setGeoJson(routesJson(data))
  style.getSourceAs<GeoJsonSource>("pauses")?.setGeoJson(pausesJson(data))
  style.getSourceAs<GeoJsonSource>("ends")?.setGeoJson(endsJson(data))
  style.getSourceAs<GeoJsonSource>(PINS_LAYER)?.setGeoJson(pinsJson(data, selected))
}

/** Takes in the whole route and every pin; a single place is shown close up (slice 2 spec §7.5). */
private fun fit(map: MapLibreMap, extent: List<LatLon>, padding: Int) {
  val places = extent.distinct()
  when {
    places.isEmpty() -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(JAPAN, 4.5))
    places.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(places[0].lat, places[0].lon), 14.0))
    else -> map.moveCamera(
      CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(places.map { LatLng(it.lat, it.lon) }).build(), padding),
    )
  }
}

/**
 * onPin gets the askId of a tapped pin, or null for a tap elsewhere. The camera takes in data once its first places
 * arrive, and then stays where the rider moves it. onMap hands the map to the emulator tests once it has drawn.
 */
@Composable
fun RouteMap(
  data: MapData,
  selected: String?,
  onPin: (String?) -> Unit,
  modifier: Modifier = Modifier,
  onMap: (MapLibreMap) -> Unit = {},
) {
  val context = LocalContext.current
  val dark = isSystemInDarkTheme()
  val density = context.resources.displayMetrics.density
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val tapped by rememberUpdatedState(onPin)
  val ready by rememberUpdatedState(onMap)
  var loaded by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
  var fitted by remember { mutableStateOf(false) }
  val view = remember {
    MapLibre.getInstance(context)
    MapView(context).apply { onCreate(null) }
  }
  DisposableEffect(lifecycle) {
    var state = Lifecycle.Event.ON_CREATE
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> view.onStart()
        Lifecycle.Event.ON_RESUME -> view.onResume()
        Lifecycle.Event.ON_PAUSE -> view.onPause()
        Lifecycle.Event.ON_STOP -> view.onStop()
        else -> Unit
      }
      state = event
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      if (state == Lifecycle.Event.ON_RESUME) view.onPause()
      if (state == Lifecycle.Event.ON_RESUME || state == Lifecycle.Event.ON_START || state == Lifecycle.Event.ON_PAUSE) view.onStop()
      view.onDestroy()
    }
  }
  LaunchedEffect(view) {
    view.getMapAsync { map ->
      map.uiSettings.isAttributionEnabled = false
      map.uiSettings.isLogoEnabled = false
      map.uiSettings.isCompassEnabled = false
      map.uiSettings.isRotateGesturesEnabled = false
      map.uiSettings.isTiltGesturesEnabled = false
      map.setStyle(Style.Builder().fromJson(styleJson(context))) { style ->
        addLayers(style, data.faint, dark)
        loaded = map to style
        // Rendered features can be asked for only once the renderer has drawn: before that MapLibre crashes. So taps
        // are listened to, and the map is handed to the tests, only from the first time it has drawn.
        var told = false
        view.addOnDidBecomeIdleListener {
          if (!told) {
            told = true
            map.addOnMapClickListener { at ->
              val point = map.projection.toScreenLocation(at)
              val reach = 20f * density
              val hit = map.queryRenderedFeatures(RectF(point.x - reach, point.y - reach, point.x + reach, point.y + reach), PINS_LAYER)
              tapped(hit.firstOrNull()?.getStringProperty("askId"))
              true
            }
            ready(map)
          }
        }
      }
    }
  }
  LaunchedEffect(loaded, data, selected) {
    val (map, style) = loaded ?: return@LaunchedEffect
    draw(style, data, selected, density)
    // Until the first places arrive the map shows Japan, and the camera fits again once they do.
    if (!fitted) {
      fit(map, data.extent, (48 * density).toInt())
      fitted = data.extent.isNotEmpty()
    }
  }
  Box(modifier) {
    AndroidView({ view }, Modifier.matchParentSize())
    val uri = LocalUriHandler.current
    Text(
      "地理院タイル",
      fontSize = 10.sp,
      color = Color(0xFF333333),
      modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xCCFFFFFF)).clickable { uri.openUri(GSI_TILES_URL) }.padding(horizontal = 4.dp, vertical = 1.dp),
    )
  }
}
