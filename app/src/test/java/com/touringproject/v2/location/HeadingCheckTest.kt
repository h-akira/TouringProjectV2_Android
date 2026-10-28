package com.touringproject.v2.location

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Slice 5 spec §4.5: SC-22 works the heading out as an ask does, from the ride's route while it records and from points
// gathered as a route's are otherwise; leaving the screen stops its watches.
class HeadingCheckTest {
  /** The fixes' own time runs with the time since boot, offset by a day; the device's clock is 5 minutes ahead. */
  private class World {
    var elapsed = 100_000L
    val gpsNow get() = elapsed + 86_400_000
    var recording = false
    var params = HeadingParams()
    var positionWatches = 0
    var positionRemoved = 0
    var routeWatches = 0
    var routeRemoved = 0
    var onPosition: ((Fix) -> Unit)? = null
    var onRoute: ((Fix) -> Unit)? = null
    val asked = mutableListOf<Fix?>()
    val views = mutableListOf<HeadingView>()

    val positions = object : PositionSource {
      override fun now() = gpsNow + 5 * 60_000
      override fun elapsed() = elapsed
      override fun lastKnown(onFix: (Fix) -> Unit) = Unit
      override fun watch(onFix: (Fix) -> Unit): Subscription {
        positionWatches += 1
        onPosition = onFix
        return Subscription { positionRemoved += 1 }
      }
    }
    val route = RouteSource { onFix ->
      routeWatches += 1
      onRoute = onFix
      Subscription { routeRemoved += 1 }
    }
    val check = HeadingCheck(
      positions, route, { recording },
      rideHeading = { here -> asked += here; HeadingResult.Found(90.0, Fix(35.0, 139.0, gpsNow - 3_000, 5.0), 20.0) },
      params = { params },
      gpsNow = { gpsNow },
    )

    /** A fix made now, north metres north of one place. */
    fun fix(north: Double, accuracy: Double? = 5.0, ageMs: Long = 0) =
      Fix(35.0 + north / 111_195.0, 139.0, gpsNow - ageMs, accuracy, elapsedMs = elapsed - ageMs)

    fun tick(scope: TestScope) {
      elapsed += HEADING_CHECK_TICK_MS
      scope.advanceTimeBy(HEADING_CHECK_TICK_MS)
      scope.runCurrent()
    }
  }

  private fun TestScope.open(w: World) = backgroundScope.launch { w.check.run { w.views += it } }.also { runCurrent() }

  @Test
  fun without_a_recording_ride_points_are_gathered_as_a_route_and_give_the_heading() = runTest {
    val w = World()
    open(w)
    assertEquals(HeadingResult.None(NoHeading.NO_POSITION), w.views.last().result)
    (0..3).forEach { step ->
      w.onRoute!!(w.fix(step * 10.0))
      w.onPosition!!(w.fix(step * 10.0, accuracy = 6.0))
      repeat(5) { w.tick(this) }
    }
    val view = w.views.last()
    val found = view.result as HeadingResult.Found
    assertEquals(0.0, found.degrees, 0.5)
    assertEquals(20.0, found.meters, 0.5)
    assertFalse(view.fromRide)
    assertEquals(6.0, view.here?.accuracy)
    // The reference is the point 20 m back, gathered 15 s ago.
    assertEquals(15_000L, view.referenceAgeMs)
  }

  @Test
  fun a_cached_fix_from_before_opening_and_an_inaccurate_one_are_not_gathered() = runTest {
    val w = World()
    open(w)
    w.onRoute!!(w.fix(-30.0, ageMs = 60_000))
    w.onRoute!!(w.fix(-20.0, accuracy = 80.0))
    w.onPosition!!(w.fix(0.0))
    w.tick(this)
    assertEquals(HeadingResult.None(NoHeading.NOT_MOVED), w.views.last().result)
  }

  @Test
  fun a_stale_position_is_no_position() = runTest {
    val w = World()
    open(w)
    w.onPosition!!(w.fix(0.0))
    repeat(31) { w.tick(this) }
    assertNull(w.views.last().here)
    assertEquals(HeadingResult.None(NoHeading.NO_POSITION), w.views.last().result)
  }

  @Test
  fun while_a_ride_records_its_route_gives_the_heading_and_nothing_is_gathered() = runTest {
    val w = World().apply { recording = true }
    open(w)
    w.onPosition!!(w.fix(0.0))
    w.tick(this)
    assertEquals(0, w.routeWatches)
    val view = w.views.last()
    assertTrue(view.fromRide)
    assertEquals(90.0, (view.result as HeadingResult.Found).degrees, 0.0)
    assertEquals(3_000L, view.referenceAgeMs)
    // The ride's route is asked with the current position, as an ask asks it.
    assertEquals(35.0, w.asked.last()!!.latitude, 0.0)
  }

  @Test
  fun a_ride_that_pauses_starts_a_new_gathering_and_one_that_resumes_ends_it() = runTest {
    val w = World().apply { recording = true }
    open(w)
    w.recording = false
    w.tick(this)
    assertEquals(1, w.routeWatches)
    assertFalse(w.views.last().fromRide)
    w.recording = true
    w.tick(this)
    assertEquals(1, w.routeRemoved)
    assertTrue(w.views.last().fromRide)
  }

  @Test
  fun the_parameters_of_the_moment_are_used() = runTest {
    val w = World()
    open(w)
    w.onRoute!!(w.fix(0.0))
    w.tick(this)
    w.onPosition!!(w.fix(12.0))
    w.tick(this)
    assertEquals(HeadingResult.None(NoHeading.NOT_MOVED), w.views.last().result)
    w.params = HeadingParams(referenceMeters = 10)
    w.tick(this)
    assertTrue(w.views.last().result is HeadingResult.Found)
  }

  // Android #83: only the newest HEADING_POINTS points gathered are kept, as a ride's route reads no more. A point 30 m
  // back, then points at a stop: the reference is found while it is among the newest, and gone once it is not.
  @Test
  fun only_the_newest_points_gathered_are_kept() = runTest {
    fun TestScope.after(atStop: Int): HeadingResult {
      val w = World()
      open(w)
      w.elapsed += 200_000
      w.onRoute!!(w.fix(-30.0, ageMs = 150_000))
      (atStop downTo 1).forEach { w.onRoute!!(w.fix(0.0, ageMs = it * 1_000L)) }
      w.onPosition!!(w.fix(0.0))
      w.tick(this)
      return w.views.last().result
    }
    assertTrue(after(HEADING_POINTS - 1) is HeadingResult.Found)
    assertEquals(HeadingResult.None(NoHeading.NOT_MOVED), after(HEADING_POINTS))
  }

  @Test
  fun leaving_removes_both_watches() = runTest {
    val w = World()
    val job = open(w)
    assertEquals(1, w.positionWatches)
    assertEquals(1, w.routeWatches)
    job.cancel()
    runCurrent()
    assertEquals(1, w.positionRemoved)
    assertEquals(1, w.routeRemoved)
  }

  // Without the location permission Fused throws: the screen still opens and says there is no position.
  @Test
  fun without_the_location_permission_there_is_no_position_and_nothing_breaks() = runTest {
    val refused = object : PositionSource {
      override fun now() = 0L
      override fun elapsed() = 0L
      override fun lastKnown(onFix: (Fix) -> Unit) = throw SecurityException("no permission")
      override fun watch(onFix: (Fix) -> Unit): Subscription = throw SecurityException("no permission")
    }
    val check = HeadingCheck(
      refused, { throw SecurityException("no permission") }, { false },
      rideHeading = { HeadingResult.None(NoHeading.NO_POSITION) }, params = { HeadingParams() }, gpsNow = { 0L },
    )
    val views = mutableListOf<HeadingView>()
    val job = backgroundScope.launch { check.run { views += it } }
    runCurrent()
    advanceTimeBy(HEADING_CHECK_TICK_MS)
    runCurrent()
    assertEquals(HeadingView(HeadingResult.None(NoHeading.NO_POSITION), null, false, null), views.last())
    job.cancel()
    runCurrent()
  }
}
