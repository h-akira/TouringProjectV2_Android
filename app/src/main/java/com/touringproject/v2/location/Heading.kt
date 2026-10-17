package com.touringproject.v2.location

import com.touringproject.v2.api.HeadingSample
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Picks the older point the Backend uses to work out the direction of travel (07 UC-5).

/** accuracy is the radius in metres the device reports (null when unknown). */
data class Fix(val latitude: Double, val longitude: Double, val timestamp: Long, val accuracy: Double?)

const val MIN_SAMPLE_DISTANCE_M = 15.0
const val MAX_SAMPLE_AGE_MS = 60_000L
// GPS jitter of a stationary phone can exceed MIN_SAMPLE_DISTANCE_M with poor fixes.
const val MAX_ACCURACY_M = 20.0

private fun accurate(f: Fix) = f.accuracy == null || f.accuracy <= MAX_ACCURACY_M

fun distanceMeters(a: Fix, b: Fix): Double {
  val r = 6_371_000.0
  fun rad(d: Double) = Math.toRadians(d)
  val dLat = rad(b.latitude - a.latitude)
  val dLon = rad(b.longitude - a.longitude)
  val h = sin(dLat / 2).pow(2) + cos(rad(a.latitude)) * cos(rad(b.latitude)) * sin(dLon / 2).pow(2)
  return 2 * r * asin(sqrt(h))
}

fun pickHeadingSample(fixes: List<Fix>, current: Fix): HeadingSample? {
  if (!accurate(current)) return null
  val newest = fixes
    .filter {
      accurate(it) &&
        it.timestamp < current.timestamp &&
        current.timestamp - it.timestamp <= MAX_SAMPLE_AGE_MS &&
        distanceMeters(it, current) >= MIN_SAMPLE_DISTANCE_M
    }
    .maxByOrNull { it.timestamp } ?: return null
  return HeadingSample(newest.latitude, newest.longitude, Instant.ofEpochMilli(newest.timestamp).toString())
}
