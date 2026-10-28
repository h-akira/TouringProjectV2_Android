package com.touringproject.v2.location

import org.junit.Assert.assertEquals
import org.junit.Test

// Android #17: a route point's age is told on the fixes' own time, which the device's clock may be minutes off.
class GpsClockTest {
  private var elapsed = 10_000L
  private var clock = 1_000_000L + 5 * 60_000
  private val gps = GpsClock({ elapsed }, { clock })

  @Test
  fun the_clock_stands_in_until_a_fix_has_been_seen() {
    assertEquals(clock, gps.now())
    gps.seen(Fix(35.0, 139.0, 990_000, 5.0))
    assertEquals(clock, gps.now())
  }

  @Test
  fun after_a_fix_the_present_runs_on_the_fixes_time_by_the_time_since_boot() {
    gps.seen(Fix(35.0, 139.0, 1_000_000, 5.0, elapsedMs = 9_000))
    assertEquals(1_001_000L, gps.now())
    elapsed += 120_000
    assertEquals(1_121_000L, gps.now())
  }

  @Test
  fun the_sources_show_every_fix_to_the_clock() {
    val fix = Fix(35.0, 139.0, 2_000_000, 5.0, elapsedMs = 10_000)
    val route = RouteSource { onFix -> onFix(fix); Subscription {} }.seenBy(gps)
    val got = mutableListOf<Fix>()
    route.watch { got += it }
    assertEquals(listOf(fix), got)
    assertEquals(2_000_000L, gps.now())
    val positions = object : PositionSource {
      override fun now() = clock
      override fun elapsed() = elapsed
      override fun lastKnown(onFix: (Fix) -> Unit) = onFix(fix.copy(timestamp = 3_000_000))
      override fun watch(onFix: (Fix) -> Unit) = Subscription {}
    }.seenBy(gps)
    positions.lastKnown { got += it }
    assertEquals(3_000_000L, gps.now())
  }
}
