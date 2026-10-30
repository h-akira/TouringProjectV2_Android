package com.touringproject.v2.location

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionTrackerTest {
  private class FakeSource(var now: Long = 100_000, var elapsed: Long = 50_000) : PositionSource {
    var last: Fix? = null
    var onFix: ((Fix) -> Unit)? = null
    var lateLastKnown: ((Fix) -> Unit)? = null
    var removed = 0
    var deferLastKnown = false

    override fun now() = now
    override fun elapsed() = elapsed
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
    val token = tracker.begin()
    tracker.end(token)
    assertEquals(1, source.removed)
  }

  // Android R1-01: the cleanup of an ask that was cancelled can run after the next ask has begun.
  @Test
  fun an_end_with_an_earlier_token_changes_nothing_of_the_newer_watch() {
    val source = FakeSource()
    val tracker = PositionTracker(source)
    val older = tracker.begin()
    val newer = tracker.begin()
    source.onFix!!(fix(99_500))
    assertNull(tracker.end(older))
    assertEquals(1, source.removed)
    assertEquals(fix(99_500), tracker.end(newer))
    assertEquals(2, source.removed)
  }

  @Test
  fun fixes_from_an_earlier_begin_are_not_mixed_in() {
    val source = FakeSource()
    val tracker = PositionTracker(source)
    tracker.begin()
    val oldCallback = source.onFix!!
    val token = tracker.begin()
    source.onFix!!(fix(99_500))
    oldCallback(fix(99_900))
    assertEquals(fix(99_500), tracker.end(token))
  }

  @Test
  fun a_last_known_point_that_arrives_after_end_is_ignored() {
    val source = FakeSource().apply { deferLastKnown = true }
    val tracker = PositionTracker(source)
    tracker.end(tracker.begin())
    source.lateLastKnown!!(fix(99_000))
    assertNull(tracker.end(tracker.begin()))
  }

  @Test
  fun a_stale_last_known_point_is_not_the_current_position() {
    val source = FakeSource().apply { last = fix(100_000 - MAX_CURRENT_AGE_MS - 1) }
    val tracker = PositionTracker(source)
    assertNull(tracker.end(tracker.begin()))
  }

  @Test
  fun the_newest_fresh_fix_is_the_current_position() {
    val source = FakeSource().apply { last = fix(90_000) }
    val tracker = PositionTracker(source)
    val token = tracker.begin()
    source.onFix!!(fix(99_000))
    source.onFix!!(fix(98_000))
    assertEquals(fix(99_000), tracker.end(token))
  }

  // Slice 5 spec §4.1: a text ask is where the device last was, within 30 s; none without a fresh fix.
  @Test
  fun a_text_ask_takes_the_last_known_fix_when_it_is_fresh() = runTest {
    val tracker = PositionTracker(FakeSource().apply { last = fix(90_000) })
    assertEquals(fix(90_000), tracker.latest())
    assertNull(PositionTracker(FakeSource().apply { last = fix(100_000 - MAX_CURRENT_AGE_MS - 1) }).latest())
  }

  @Test
  fun no_last_known_fix_gives_none_after_a_short_wait() = runTest {
    // Fused answers a success without a fix by never calling back.
    assertNull(PositionTracker(FakeSource()).latest())
    assertEquals(LATEST_WAIT_MS, testScheduler.currentTime)
    val refused = object : PositionSource {
      override fun now() = 100_000L
      override fun elapsed() = 50_000L
      override fun lastKnown(onFix: (Fix) -> Unit) = throw SecurityException("no permission")
      override fun watch(onFix: (Fix) -> Unit) = Subscription {}
    }
    assertNull(PositionTracker(refused).latest())
  }

  // Android #17: the device's clock may be minutes off a fix's own (GNSS) time; the time since boot tells its age.
  @Test
  fun a_fix_is_as_old_as_the_time_since_boot_says_whatever_the_clock_says() = runTest {
    val ahead = FakeSource(now = 100_000 + 5 * 60_000, elapsed = 50_000)
    val tracker = PositionTracker(ahead)
    val token = tracker.begin()
    ahead.onFix!!(Fix(35.0, 139.0, 100_000, 5.0, elapsedMs = 45_000))
    assertEquals(100_000L, tracker.end(token)?.timestamp)
    val stale = FakeSource(now = 100_000, elapsed = 50_000).apply {
      last = Fix(35.0, 139.0, 99_000, 5.0, elapsedMs = 50_000 - MAX_CURRENT_AGE_MS - 1)
    }
    assertNull(PositionTracker(stale).latest())
  }
}
