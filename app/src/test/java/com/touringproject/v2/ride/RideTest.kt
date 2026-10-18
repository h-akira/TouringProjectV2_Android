package com.touringproject.v2.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MAX = 24 * HOUR_MS

class RideTest {
  private val started = newRide("r1", "箱根", 1_000, 7, MAX)

  @Test
  fun a_new_ride_records_one_open_segment_with_its_deadline() {
    assertEquals(RideStatus.RECORDING, started.status)
    assertEquals(listOf(Segment(0, 1_000, null, 7)), started.segments)
    assertEquals(1_000 + MAX, started.deadline)
  }

  @Test
  fun pausing_closes_the_segment_and_resuming_opens_the_next_with_a_new_deadline() {
    val paused = started.pause(5_000)
    assertEquals(Segment(0, 1_000, 5_000, 7), paused.segments.single())
    assertNull(paused.deadline)
    val resumed = paused.resume(9_000, 7, MAX)
    assertEquals(Segment(1, 9_000, null, 7), resumed.segments.last())
    assertEquals(9_000 + MAX, resumed.deadline)
  }

  @Test
  fun recorded_time_and_pauses_leave_out_the_gaps() {
    val ride = started.pause(5_000).resume(9_000, 7, MAX).pause(10_000).resume(20_000, 7, MAX)
    assertEquals(4_000 + 1_000 + 2_000, ride.recordedMs(22_000))
    assertEquals(Pauses(2, 4_000 + 10_000), ride.pauses())
  }

  @Test
  fun ending_closes_the_open_segment_at_the_given_time_and_keeps_the_new_name() {
    val ended = started.end(3_000, EndReason.MANUAL, 3_500, "箱根から御殿場へ")
    assertEquals(3_000L, ended.endedAt())
    assertEquals("箱根から御殿場へ", ended.name)
    assertEquals(EndReason.MANUAL, ended.endReason)
    assertEquals(3_500L, ended.updatedAt)
  }

  @Test
  fun an_end_before_the_segment_started_closes_it_at_its_start() {
    assertEquals(1_000L, started.end(500, EndReason.SHUTDOWN, 600).endedAt())
  }

  @Test
  fun names_are_trimmed_required_and_at_most_40_characters() {
    assertEquals("箱根", rideName("  箱根 "))
    assertNull(rideName("   "))
    assertEquals("あ".repeat(40), rideName("あ".repeat(40)))
    assertNull(rideName("あ".repeat(41)))
  }

  @Test
  fun the_distance_steps_never_join_two_segments() {
    val a = RoutePoint(0, 0, 35.0, 139.0, 5.0)
    val b = RoutePoint(0, 5_000, 35.001, 139.0, 5.0)
    val c = RoutePoint(1, 9_000, 35.002, 139.0, 5.0)
    assertEquals(111.2, stepMeters(a, b), 0.5)
    assertEquals(0.0, stepMeters(b, c), 0.0)
    assertEquals(0.0, stepMeters(null, a), 0.0)
  }
}

class ReconcileTest {
  private val recording = newRide("r1", "箱根", 1_000, 7, MAX).copy(lastAliveAt = 60_000)

  @Test
  fun nothing_is_left_over() {
    assertEquals(Reconcile.Nothing, reconcile(null, 2_000, 7))
  }

  @Test
  fun a_reboot_ends_the_ride_when_it_was_last_seen_alive() {
    assertEquals(Reconcile.EndShutdown(60_000), reconcile(recording, 120_000, 8))
  }

  @Test
  fun a_reboot_ends_a_paused_ride_at_the_pause() {
    val paused = recording.pause(90_000)
    assertEquals(Reconcile.EndShutdown(90_000), reconcile(paused, 120_000, 8))
  }

  @Test
  fun a_crash_past_the_deadline_ends_the_ride_at_the_deadline() {
    assertEquals(Reconcile.EndAuto(1_000 + MAX), reconcile(recording, 2_000 + MAX, 7))
  }

  @Test
  fun a_crash_before_the_deadline_resumes_recording() {
    assertEquals(Reconcile.Resume, reconcile(recording, 120_000, 7))
  }

  @Test
  fun a_paused_ride_stays_paused_and_an_unknown_boot_count_is_not_a_reboot() {
    assertEquals(Reconcile.KeepPaused, reconcile(recording.pause(90_000), 120_000, 7))
    assertEquals(Reconcile.Resume, reconcile(recording, 120_000, null))
  }
}
