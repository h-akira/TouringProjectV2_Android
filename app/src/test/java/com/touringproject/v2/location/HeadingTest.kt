package com.touringproject.v2.location

import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingTest {
  private val here = Fix(35.6812, 139.7671, 100_000, 5.0)
  private val behind = Fix(35.6806, 139.7671, 90_000, 5.0) // about 67 m south

  @Test
  fun distance() {
    val d = distanceMeters(behind, here)
    assertTrue(d in 60.0..75.0)
  }
}
