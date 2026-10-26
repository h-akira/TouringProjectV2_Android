package com.touringproject.v2.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

// Slice 5 spec §4.2 and its table of scenes. Points are laid out in metres north and east of one place.
private const val LAT = 35.0
private const val LON = 139.0
private const val M_PER_DEG = 111_195.0

private fun at(north: Double, east: Double, seconds: Long, accuracy: Double? = 5.0) =
  Fix(LAT + north / M_PER_DEG, LON + east / (M_PER_DEG * cos(Math.toRadians(LAT))), seconds * 1_000, accuracy)

private val DEFAULTS = HeadingParams()

private fun found(result: HeadingResult): HeadingResult.Found {
  assertTrue("expected a heading, got $result", result is HeadingResult.Found)
  return result as HeadingResult.Found
}

class FindHeadingTest {
  @Test
  fun bearings_run_clockwise_from_north_and_never_reach_360() {
    val o = at(0.0, 0.0, 0)
    assertEquals(0.0, bearingDegrees(o, at(100.0, 0.0, 0)), 0.1)
    assertEquals(90.0, bearingDegrees(o, at(0.0, 100.0, 0)), 0.1)
    assertEquals(180.0, bearingDegrees(o, at(-100.0, 0.0, 0)), 0.1)
    assertEquals(270.0, bearingDegrees(o, at(0.0, -100.0, 0)), 0.1)
    val nearlyNorth = bearingDegrees(o, at(100.0, -1e-9, 0))
    assertTrue(nearlyNorth >= 0.0 && nearlyNorth < 360.0)
  }

  // Riding north, a point every 5 s and 10 m: the reference is the newest point 15 m behind.
  @Test
  fun riding_takes_the_heading_from_a_few_seconds_back() {
    val route = (0..5).map { at(it * 10.0, 0.0, it * 5L) }
    val here = at(56.0, 0.0, 27)
    val heading = found(findHeading(route, here, 27_000, DEFAULTS))
    assertEquals(0.0, heading.degrees, 0.5)
    assertEquals(route[4], heading.reference)
  }

  @Test
  fun after_a_turn_the_heading_follows_the_new_road_once_it_has_gone_far_enough() {
    // North to the corner, then east.
    val north = (0..4).map { at(-40.0 + it * 10.0, 0.0, it * 5L) }
    val justTurned = found(findHeading(north, at(0.0, 10.0, 22), 22_000, DEFAULTS))
    assertTrue("between the two roads: ${justTurned.degrees}", justTurned.degrees > 0.0 && justTurned.degrees < 90.0)
    val east = north + at(0.0, 10.0, 25)
    val turned = found(findHeading(east, at(0.0, 20.0, 27), 27_000, DEFAULTS))
    assertEquals(90.0, turned.degrees, 0.5)
  }

  // A red light: no point is added while the bike stands, so the points it came along still give the heading.
  @Test
  fun a_stop_of_two_minutes_keeps_the_heading_it_came_in_with() {
    val route = (0..4).map { at(-40.0 + it * 10.0, 0.0, it * 5L) }
    val here = at(0.5, 0.3, 140, accuracy = 8.0)
    assertEquals(0.0, found(findHeading(route, here, 140_000, DEFAULTS)).degrees, 2.0)
  }

  @Test
  fun a_stop_longer_than_the_hold_has_no_heading() {
    val route = (0..4).map { at(-40.0 + it * 10.0, 0.0, it * 5L) }
    val here = at(0.0, 0.0, 20 + 10 * 60 + 1)
    assertEquals(HeadingResult.None(NoHeading.HELD_TOO_LONG), findHeading(route, here, here.timestamp, DEFAULTS))
  }

  @Test
  fun a_reference_point_that_is_not_accurate_enough_is_passed_over() {
    val route = listOf(at(-30.0, 0.0, 0), at(-20.0, 0.0, 5, accuracy = 25.0))
    val heading = found(findHeading(route, at(0.0, 0.0, 10), 10_000, DEFAULTS))
    assertEquals(route[0], heading.reference)
    val unknown = listOf(at(-20.0, 0.0, 5, accuracy = null))
    assertEquals(unknown[0], found(findHeading(unknown, at(0.0, 0.0, 10), 10_000, DEFAULTS)).reference)
  }

  @Test
  fun without_a_good_position_here_there_is_no_heading() {
    val route = listOf(at(-30.0, 0.0, 0))
    assertEquals(HeadingResult.None(NoHeading.NO_POSITION), findHeading(route, null, 10_000, DEFAULTS))
    assertEquals(HeadingResult.None(NoHeading.POOR_ACCURACY), findHeading(route, at(0.0, 0.0, 10, accuracy = 21.0), 10_000, DEFAULTS))
    assertEquals(0.0, found(findHeading(route, at(0.0, 0.0, 10, accuracy = null), 10_000, DEFAULTS)).degrees, 0.5)
  }

  @Test
  fun a_segment_that_has_not_gone_the_reference_distance_has_no_heading() {
    val route = listOf(at(0.0, 0.0, 0), at(10.0, 0.0, 5))
    assertEquals(HeadingResult.None(NoHeading.NOT_MOVED), findHeading(route, at(12.0, 0.0, 6), 6_000, DEFAULTS))
    assertEquals(HeadingResult.None(NoHeading.NOT_MOVED), findHeading(emptyList(), at(12.0, 0.0, 6), 6_000, DEFAULTS))
  }

  // A route point taken after here lies ahead of it; a heading from it would point backwards.
  @Test
  fun points_after_here_are_not_used() {
    val route = listOf(at(-30.0, 0.0, 0), at(30.0, 0.0, 20))
    assertEquals(0.0, found(findHeading(route, at(0.0, 0.0, 10), 10_000, DEFAULTS)).degrees, 0.5)
  }

  @Test
  fun the_parameters_change_the_next_heading() {
    val route = listOf(at(-40.0, 10.0, 0), at(-20.0, 0.0, 5))
    val here = at(0.0, 0.0, 10)
    assertEquals(route[1], found(findHeading(route, here, 10_000, DEFAULTS)).reference)
    assertEquals(route[0], found(findHeading(route, here, 10_000, HeadingParams(referenceMeters = 30))).reference)
    assertEquals(
      HeadingResult.None(NoHeading.POOR_ACCURACY),
      findHeading(route, here.copy(accuracy = 12.0), 10_000, HeadingParams(accuracyMeters = 10)),
    )
    assertEquals(HeadingResult.None(NoHeading.HELD_TOO_LONG), findHeading(route, here, 5_000 + 2 * 60_000 + 1, HeadingParams(holdMinutes = 2)))
  }
}
