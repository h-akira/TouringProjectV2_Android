package com.touringproject.v2.ride

import com.touringproject.v2.location.Fix
import com.touringproject.v2.location.HEADING_POINTS
import com.touringproject.v2.location.HeadingParams
import com.touringproject.v2.location.HeadingResult
import com.touringproject.v2.location.findHeading
import kotlinx.coroutines.CancellationException

/**
 * The heading of the ride recording or paused (slice 5 spec §4.1): from the points of its last segment only, since a
 * ride may resume far from where it paused (a ferry). Asks, unreached asks and point memos all go through here, so
 * each takes its heading the same way, as does SC-22 while the ride records. Without a ride, or when its points cannot
 * be read, there is no route. now is the present on the points' own time (GpsClock), which the hold is measured
 * against (Android #17).
 */
class RouteHeading(
  private val ride: () -> Ride?,
  private val points: suspend (rideId: String, segment: Int, count: Int) -> List<RoutePoint>,
  private val params: () -> HeadingParams,
  private val now: () -> Long,
  private val log: (String, Throwable) -> Unit,
) {
  suspend fun find(here: Fix?): HeadingResult {
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
    return findHeading(route, here, now(), params())
  }
}
