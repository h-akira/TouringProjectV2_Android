package com.touringproject.v2.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionTrackerTest {
  private class FakeSource(var now: Long = 100_000) : PositionSource {
    var last: Fix? = null
    var onFix: ((Fix) -> Unit)? = null
    var lateLastKnown: ((Fix) -> Unit)? = null
    var removed = 0
    var deferLastKnown = false

    override fun now() = now
    override fun lastKnown(onFix: (Fix) -> Unit) {
      if (deferLastKnown) lateLastKnown = onFix else last?.let(onFix)
    }
    override fun watch(onFix: (Fix) -> Unit): Subscription {
      this.onFix = onFix
      return Subscription { removed++ }
    }
  }

  private fun fix(t: Long) = Fix(35.0, 139.0, t, 5.0)

  @Test
  fun end_removes_the_watch() {
    val source = FakeSource()
    val tracker = PositionTracker(source)
    tracker.begin()
    tracker.end()
    assertEquals(1, source.removed)
  }

  @Test
  fun fixes_from_an_earlier_begin_are_not_mixed_in() {
    val source = FakeSource()
    val tracker = PositionTracker(source)
    tracker.begin()
    val oldCallback = source.onFix!!
    tracker.begin()
    source.onFix!!(fix(99_500))
    oldCallback(fix(99_900))
    assertEquals(fix(99_500), tracker.end())
  }

  @Test
  fun a_last_known_point_that_arrives_after_end_is_ignored() {
    val source = FakeSource().apply { deferLastKnown = true }
    val tracker = PositionTracker(source)
    tracker.begin()
    tracker.end()
    source.lateLastKnown!!(fix(99_000))
    tracker.begin()
    assertNull(tracker.end())
  }

  @Test
  fun a_stale_last_known_point_is_not_the_current_position() {
    val source = FakeSource().apply { last = fix(100_000 - MAX_CURRENT_AGE_MS - 1) }
    val tracker = PositionTracker(source)
    tracker.begin()
    assertNull(tracker.end())
  }

  @Test
  fun the_newest_fresh_fix_is_the_current_position() {
    val source = FakeSource().apply { last = fix(90_000) }
    val tracker = PositionTracker(source)
    tracker.begin()
    source.onFix!!(fix(99_000))
    source.onFix!!(fix(98_000))
    assertEquals(fix(99_000), tracker.end())
  }
}
