package com.touringproject.v2.location

import kotlinx.coroutines.delay

/** How often SC-22 works the heading out again: the current position comes every second. */
const val HEADING_CHECK_TICK_MS = 1_000L

/**
 * What SC-22 shows at one moment (slice 5 spec §4.5). here is the current position (null when there is no fresh one);
 * fromRide tells whether the recording ride's route was the material; referenceAgeMs is how old the reference point is,
 * on the points' own time (null without a heading).
 */
data class HeadingView(val result: HeadingResult, val here: Fix?, val fromRide: Boolean, val referenceAgeMs: Long?)

/**
 * SC-22's heading, worked out as an ask's is (findHeading), every HEADING_CHECK_TICK_MS while run() runs. The current
 * position is the newest fresh fix of a 1 s watch, as an ask's recording takes it. While a ride records, the material is
 * its route (rideHeading, which is RouteHeading). Otherwise (no ride, or a paused one, whose route is not watched) the
 * points are gathered here, asked for and sifted as a ride's route is (acceptRoutePoint, isSince), and the newest
 * HEADING_POINTS kept only in memory; they are dropped when a ride starts recording, when one stops (gathering starts again) and when run() ends.
 * Cancelling run() removes both watches; a recording ride's own route watch is the service's and goes on.
 */
class HeadingCheck(
  private val positions: PositionSource,
  private val route: RouteSource,
  private val recording: () -> Boolean,
  private val rideHeading: suspend (here: Fix?) -> HeadingResult,
  private val params: () -> HeadingParams,
  /** The present on the fixes' own time (GpsClock). */
  private val gpsNow: () -> Long,
) {
  private val lock = Any()
  private var latest: Fix? = null
  private val points = mutableListOf<Fix>()
  // The generation of the gathering under way; a fix of an earlier one is not taken.
  private var gathering = 0

  suspend fun run(show: (HeadingView) -> Unit) {
    val watch = runCatching { positions.watch(::seen) }.getOrNull()
    runCatching { positions.lastKnown(::seen) }
    var routeWatch: Subscription? = null
    var wasRecording: Boolean? = null
    try {
      while (true) {
        val nowRecording = recording()
        if (nowRecording != wasRecording) {
          routeWatch?.remove()
          routeWatch = if (nowRecording) {
            synchronized(lock) { points.clear(); gathering += 1 }
            null
          } else {
            gather()
          }
          wasRecording = nowRecording
        }
        show(view(nowRecording))
        delay(HEADING_CHECK_TICK_MS)
      }
    } finally {
      routeWatch?.remove()
      watch?.remove()
      synchronized(lock) { points.clear(); latest = null; gathering += 1 }
    }
  }

  private fun seen(fix: Fix) = synchronized(lock) {
    val last = latest
    if (last == null || (fix.elapsedMs ?: fix.timestamp) >= (last.elapsedMs ?: last.timestamp)) latest = fix
  }

  // Starts gathering anew: only fixes made from now on, as a segment's route takes them (RideController.onFix).
  private fun gather(): Subscription? {
    val since = positions.elapsed()
    val sinceClock = positions.now()
    val gen = synchronized(lock) { points.clear(); ++gathering }
    return runCatching {
      route.watch { fix ->
        synchronized(lock) {
          if (gen == gathering && acceptRoutePoint(fix) && fix.isSince(since, sinceClock)) {
            points += fix
            // As many as a ride's route is read for, the newest (Android #83).
            if (points.size > HEADING_POINTS) points.removeAt(0)
          }
        }
      }
    }.getOrNull()
  }

  private suspend fun view(fromRide: Boolean): HeadingView {
    val (here, route) = synchronized(lock) { latest?.takeIf { it.isFresh(positions.now(), positions.elapsed()) } to points.toList() }
    val result = if (fromRide) rideHeading(here) else findHeading(route, here, gpsNow(), params())
    val age = (result as? HeadingResult.Found)?.let { gpsNow() - it.reference.timestamp }
    return HeadingView(result, here, fromRide, age)
  }
}
