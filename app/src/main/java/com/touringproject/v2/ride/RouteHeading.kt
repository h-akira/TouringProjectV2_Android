package com.touringproject.v2.ride

import com.touringproject.v2.location.Fix
import com.touringproject.v2.location.HeadingParams
import com.touringproject.v2.location.HeadingResult
import com.touringproject.v2.location.findHeading
import kotlinx.coroutines.CancellationException

/**
 * How many of the segment's last points are read. Points are 10 m apart at least, so a reference 30 m back is a few
 * points away; the rest only covers points jittering at a stop.
 */
const val HEADING_POINTS = 120

/**
 * The heading of the ride recording or paused (slice 5 spec §4.1): from the points of its last segment only, since a
 * ride may resume far from where it paused (a ferry). Asks, unreached asks and point memos all go through here, so
 * each takes its heading the same way. Without a ride, or when its points cannot be read, there is no route.
 */
class RouteHeading(
  private val ride: () -> Ride?,
  private val points: suspend (rideId: String, segment: Int, count: Int) -> List<RoutePoint>,
  private val params: () -> HeadingParams,
  private val log: (String, Throwable) -> Unit,
) {
  suspend fun find(here: Fix?, now: Long): HeadingResult {
    val active = ride()
    val route = if (active == null) {
      emptyList()
    } else {
      try {
        points(active.id, active.segments.last().seq, HEADING_POINTS).map { Fix(it.lat, it.lon, it.t, it.accuracy) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("heading_points_failed rideId=${active.id}", e)
        emptyList()
      }
    }
    return findHeading(route, here, now, params())
  }
}
