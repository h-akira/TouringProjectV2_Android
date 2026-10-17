package com.touringproject.v2.location

import com.touringproject.v2.api.HeadingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingTest {
  private val here = Fix(35.6812, 139.7671, 100_000, 5.0)
  private val behind = Fix(35.6806, 139.7671, 90_000, 5.0) // about 67 m south
  private val near = Fix(35.68119, 139.7671, 95_000, 5.0) // about 1 m

  @Test
  fun distance() {
    val d = distanceMeters(behind, here)
    assertTrue(d in 60.0..75.0)
  }

  @Test
  fun picks_the_newest_point_far_enough_behind() {
    val older = behind.copy(timestamp = 80_000)
    assertEquals(HeadingSample(35.6806, 139.7671, "1970-01-01T00:01:30Z"), pickHeadingSample(listOf(older, behind, near, here), here))
  }

  @Test
  fun test_no_sample_when_stationary() {
    assertNull(pickHeadingSample(listOf(near, here), here))
  }

  @Test
  fun ignores_points_older_than_a_minute_and_points_not_before_the_current_one() {
    assertNull(pickHeadingSample(listOf(behind.copy(timestamp = 30_000)), here))
    assertNull(pickHeadingSample(listOf(behind.copy(timestamp = 100_000)), here))
  }

  @Test
  fun test_no_sample_from_inaccurate_points() {
    assertNull(pickHeadingSample(listOf(behind.copy(accuracy = 30.0)), here))
  }

  @Test
  fun no_sample_when_the_current_point_itself_is_inaccurate() {
    assertNull(pickHeadingSample(listOf(behind), here.copy(accuracy = 30.0)))
  }

  @Test
  fun unknown_accuracy_is_accepted() {
    assertEquals(35.6806, pickHeadingSample(listOf(behind.copy(accuracy = null)), here.copy(accuracy = null))!!.latitude, 0.0)
  }
}
