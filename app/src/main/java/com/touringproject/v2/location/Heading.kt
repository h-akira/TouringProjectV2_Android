package com.touringproject.v2.location

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The direction of travel, worked out on the device from the ride's route (slice 5 spec §4, adr/0013).

/**
 * accuracy is the radius in metres the device reports (null when unknown). timestamp is the fix's own time (often the
 * GNSS time), which may be off the device's clock; elapsedMs is the time since boot when the fix was made, which is
 * what tells how old it is (Android #17). A point read back from the database has none.
 */
data class Fix(val latitude: Double, val longitude: Double, val timestamp: Long, val accuracy: Double?, val elapsedMs: Long? = null)

// Slice 5 spec §4.3: the rider changes these (SC-22); the defaults hold the heading through a long red light.
const val DEFAULT_HEADING_REFERENCE_M = 15
const val DEFAULT_HEADING_ACCURACY_M = 20
const val DEFAULT_HEADING_HOLD_MINUTES = 10

/**
 * referenceMeters: how far the point the heading is taken from must be from here. accuracyMeters: the worst accuracy
 * of that point and of here. holdMinutes: how old that point may be, so a heading held at a stop runs out.
 */
data class HeadingParams(
  val referenceMeters: Int = DEFAULT_HEADING_REFERENCE_M,
  val accuracyMeters: Int = DEFAULT_HEADING_ACCURACY_M,
  val holdMinutes: Int = DEFAULT_HEADING_HOLD_MINUTES,
)

/** Why there is no heading (SC-22 tells the rider). */
enum class NoHeading { NO_POSITION, POOR_ACCURACY, NOT_MOVED, HELD_TOO_LONG }

sealed interface HeadingResult {
  /** reference is the route point the heading is taken from, meters its distance from here. */
  data class Found(val degrees: Double, val reference: Fix, val meters: Double) : HeadingResult
  data class None(val reason: NoHeading) : HeadingResult
}

val HeadingResult.degrees: Double? get() = (this as? HeadingResult.Found)?.degrees

/** Degrees clockwise from true north, 0 or more and less than 360. */
fun bearingDegrees(from: Fix, to: Fix): Double {
  fun rad(d: Double) = Math.toRadians(d)
  val dLon = rad(to.longitude - from.longitude)
  val y = sin(dLon) * cos(rad(to.latitude))
  val x = cos(rad(from.latitude)) * sin(rad(to.latitude)) - sin(rad(from.latitude)) * cos(rad(to.latitude)) * cos(dLon)
  return (Math.toDegrees(atan2(y, x)) % 360 + 360) % 360
}

/**
 * Slice 5 spec §4.2. route is the points of the ride's last segment, in any order. Going back from the newest point
 * not after here, the first one accurate enough and far enough from here is the reference; the heading runs from it to
 * here. A reference older than the hold (measured at now) gives none, as does no or a poor position here.
 */
fun findHeading(route: List<Fix>, here: Fix?, now: Long, params: HeadingParams): HeadingResult {
  if (here == null) return HeadingResult.None(NoHeading.NO_POSITION)
  val accuracy = params.accuracyMeters.toDouble()
  if (here.accuracy != null && here.accuracy > accuracy) return HeadingResult.None(NoHeading.POOR_ACCURACY)
  val reference = route
    .filter { it.timestamp <= here.timestamp }
    .sortedByDescending { it.timestamp }
    .firstOrNull { (it.accuracy == null || it.accuracy <= accuracy) && distanceMeters(it, here) >= params.referenceMeters }
    ?: return HeadingResult.None(NoHeading.NOT_MOVED)
  if (now - reference.timestamp > params.holdMinutes * 60_000L) return HeadingResult.None(NoHeading.HELD_TOO_LONG)
  return HeadingResult.Found(bearingDegrees(reference, here), reference, distanceMeters(reference, here))
}

fun distanceMeters(a: Fix, b: Fix): Double {
  val r = 6_371_000.0
  fun rad(d: Double) = Math.toRadians(d)
  val dLat = rad(b.latitude - a.latitude)
  val dLon = rad(b.longitude - a.longitude)
  val h = sin(dLat / 2).pow(2) + cos(rad(a.latitude)) * cos(rad(b.latitude)) * sin(dLon / 2).pow(2)
  return 2 * r * asin(sqrt(h))
}
