package com.touringproject.v2.ride

import com.touringproject.v2.location.Fix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val MAX = 24 * HOUR_MS

/** An in-memory store that can be told to fail, and a world that records what the controller asked of it. */
private class World(var now: Long = 1_000, var boot: Int? = 7) {
  val rides = mutableMapOf<String, Ride>()
  val points = mutableMapOf<String, MutableList<RoutePoint>>()
  var failWrites = false
  /** How many of the next saves fail. */
  var failSaves = 0
  /** How many of the next reads of the active ride fail. */
  var failActive = 0
  /** When set, a save waits for it before writing. */
  var gate: CompletableDeferred<Unit>? = null
  val events = mutableListOf<String>()
  val autoEnded = mutableListOf<Ride>()
  var changes = 0
  /** The longest recording time the rider has chosen (SC-16). */
  var max = MAX
  var pointsAdded = 0
  private var ids = 0

  val store = object : RideStore {
    override suspend fun active(): Ride? {
      if (failActive > 0) {
        failActive -= 1
        throw IOException("disk I/O error")
      }
      return rides.values.firstOrNull { it.status != RideStatus.ENDED }
    }
    override suspend fun save(ride: Ride) {
      gate?.await()
      if (failWrites) throw IOException("disk full")
      if (failSaves > 0) {
        failSaves -= 1
        throw IOException("disk full")
      }
      rides[ride.id] = ride
    }
    override suspend fun ride(id: String): Ride? = rides[id]
    override suspend fun lastPoint(rideId: String) = points[rideId]?.lastOrNull()
    override suspend fun addPoint(rideId: String, point: RoutePoint, distanceMeters: Double, aliveAt: Long) {
      if (failWrites) throw IOException("disk full")
      points.getOrPut(rideId) { mutableListOf() } += point
      rides[rideId] = rides.getValue(rideId).copy(distanceMeters = distanceMeters, lastAliveAt = aliveAt)
    }
    // Like the UPDATE in Room: a ride that was never saved is simply not touched. addPoint above fails on it, like
    // the foreign key does.
    override suspend fun markAlive(rideId: String, at: Long) {
      if (failWrites) throw IOException("disk full")
      rides[rideId]?.let { rides[rideId] = it.copy(lastAliveAt = at) }
    }
  }

  val controller = RideController(
    RideDeps(
      now = { now },
      bootCount = { boot },
      newId = { "r${++ids}" },
      maxRecordingMs = { max },
      store = store,
      alarm = object : DeadlineAlarm {
        override fun set(rideId: String, at: Long) { events += "alarm:$at" }
        override fun cancel() { events += "alarm:cancel" }
      },
      recording = { events += "recording:$it" },
      autoEnded = { autoEnded += it },
      changed = { changes += 1 },
      pointAdded = { pointsAdded += 1 },
      log = { _, _ -> },
    ),
  )

  fun stored() = rides.values.single()
}

private fun fix(t: Long, lat: Double, accuracy: Double? = 5.0) = Fix(lat, 139.0, t, accuracy)

class RideControllerTest {
  @Test
  fun start_saves_the_ride_sets_its_alarm_and_keeps_the_service_recording() = runTest {
    val w = World()
    w.controller.load()
    assertTrue(w.controller.start(" 箱根 "))
    assertEquals("箱根", w.stored().name)
    assertEquals(listOf("alarm:${1_000 + MAX}", "recording:true"), w.events)
    assertEquals("r1", w.controller.active.value?.id)
  }

  @Test
  fun only_one_ride_at_a_time_and_never_without_a_name() = runTest {
    val w = World()
    w.controller.load()
    assertFalse(w.controller.start("  "))
    assertTrue(w.controller.start("箱根"))
    assertFalse(w.controller.start("御殿場"))
    assertEquals(1, w.rides.size)
  }

  @Test
  fun pause_stops_the_service_and_the_alarm_and_resume_starts_both_again() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.events.clear()
    w.now = 5_000
    w.controller.pause()
    assertEquals(RideStatus.PAUSED, w.stored().status)
    assertEquals(listOf("alarm:cancel", "recording:false"), w.events)
    w.events.clear()
    w.now = 9_000
    w.controller.resume()
    assertEquals(listOf("alarm:${9_000 + MAX}", "recording:true"), w.events)
    assertEquals(2, w.stored().segments.size)
  }

  @Test
  fun points_add_up_within_a_segment_and_inaccurate_ones_are_dropped() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.controller.onFix(fix(2_000, 35.000))
    w.controller.onFix(fix(7_000, 35.001))
    w.controller.onFix(fix(12_000, 35.002, accuracy = 80.0))
    assertEquals(2, w.points.getValue("r1").size)
    assertEquals(111.2, w.stored().distanceMeters, 0.5)
    w.controller.pause()
    w.controller.resume()
    w.controller.onFix(fix(20_000, 35.010))
    // The first point after a resume adds nothing: the paused stretch is not ridden distance.
    assertEquals(111.2, w.controller.active.value!!.distanceMeters, 0.5)
  }

  @Test
  fun points_while_paused_are_not_recorded() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.controller.pause()
    w.controller.onFix(fix(2_000, 35.0))
    assertNull(w.points["r1"])
  }

  @Test
  fun the_first_call_at_or_past_the_deadline_ends_the_ride_at_the_deadline_and_says_so() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 1_000 + MAX + 30_000
    w.controller.heartbeat()
    val ended = w.stored()
    assertEquals(RideStatus.ENDED, ended.status)
    assertEquals(EndReason.AUTO, ended.endReason)
    assertEquals(1_000 + MAX, ended.endedAt())
    assertEquals(listOf(ended), w.autoEnded)
    assertNull(w.controller.active.value)
    // The alarm coming afterwards finds nothing to do.
    w.controller.deadlineReached()
    assertEquals(1, w.autoEnded.size)
  }

  @Test
  fun a_point_past_the_deadline_ends_the_ride_instead_of_being_recorded() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 1_000 + MAX
    w.controller.onFix(fix(1_000 + MAX, 35.0))
    assertNull(w.points["r1"])
    assertEquals(EndReason.AUTO, w.stored().endReason)
  }

  @Test
  fun the_alarm_before_the_deadline_does_nothing() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = MAX
    w.controller.deadlineReached()
    assertEquals(RideStatus.RECORDING, w.stored().status)
  }

  @Test
  fun a_shutdown_ends_the_ride_now_without_a_notice() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 50_000
    w.controller.shutdown()
    assertEquals(EndReason.SHUTDOWN, w.stored().endReason)
    assertEquals(50_000L, w.stored().endedAt())
    assertTrue(w.autoEnded.isEmpty())
  }

  @Test
  fun end_keeps_the_ride_when_the_new_name_is_empty() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    assertFalse(w.controller.end(" "))
    assertEquals(RideStatus.RECORDING, w.stored().status)
    assertTrue(w.controller.end("箱根から御殿場へ"))
    assertEquals("箱根から御殿場へ", w.stored().name)
    assertEquals(EndReason.MANUAL, w.stored().endReason)
  }

  @Test
  fun load_after_a_crash_resumes_and_continues_the_distance_from_the_last_point() = runTest {
    val before = World()
    before.controller.load()
    before.controller.start("箱根")
    before.controller.onFix(fix(2_000, 35.000))
    val after = World(now = 30_000).apply {
      rides += before.rides
      points += before.points
    }
    after.controller.load()
    assertEquals(listOf("alarm:${1_000 + MAX}", "recording:true"), after.events)
    after.controller.onFix(fix(31_000, 35.001))
    assertEquals(111.2, after.stored().distanceMeters, 0.5)
  }

  @Test
  fun load_after_a_reboot_ends_the_ride_and_does_not_start_the_service() = runTest {
    val before = World()
    before.controller.load()
    before.controller.start("箱根")
    before.now = 60_000
    before.controller.heartbeat()
    val after = World(now = 300_000, boot = 8).apply { rides += before.rides }
    after.controller.load()
    assertEquals(EndReason.SHUTDOWN, after.stored().endReason)
    assertEquals(60_000L, after.stored().endedAt())
    assertEquals(listOf("alarm:cancel", "recording:false"), after.events)
  }

  @Test
  fun load_past_the_deadline_ends_the_ride_and_says_so() = runTest {
    val before = World()
    before.controller.load()
    before.controller.start("箱根")
    val after = World(now = 2_000 + MAX).apply { rides += before.rides }
    after.controller.load()
    assertEquals(EndReason.AUTO, after.stored().endReason)
    assertEquals(1, after.autoEnded.size)
  }

  @Test
  fun a_fix_from_before_the_segment_started_is_not_recorded() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 5_000
    w.controller.pause()
    w.now = 9_000
    w.controller.resume()
    w.controller.onFix(fix(4_000, 35.0))
    assertNull(w.points["r1"])
    w.controller.onFix(fix(9_500, 35.0))
    assertEquals(1, w.points.getValue("r1").size)
  }

  @Test
  fun load_of_a_paused_ride_keeps_it_paused_without_the_service() = runTest {
    val before = World()
    before.controller.load()
    before.controller.start("箱根")
    before.controller.pause()
    val after = World(now = 300_000).apply { rides += before.rides }
    after.controller.load()
    assertEquals(RideStatus.PAUSED, after.controller.active.value?.status)
    assertTrue(after.events.isEmpty())
  }

  @Test
  fun the_alarm_after_the_rider_ended_does_nothing() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.controller.end("箱根")
    w.now = 2_000 + MAX
    w.controller.deadlineReached()
    assertEquals(EndReason.MANUAL, w.stored().endReason)
    assertTrue(w.autoEnded.isEmpty())
  }

  @Test
  fun a_ride_starts_even_when_it_cannot_be_saved() = runTest {
    val w = World()
    w.controller.load()
    w.failWrites = true
    assertTrue(w.controller.start("箱根"))
    assertTrue(w.controller.storeFailed.value)
    assertEquals(RideStatus.RECORDING, w.controller.active.value?.status)
    assertTrue("recording:true" in w.events)
  }

  @Test
  fun a_ride_whose_first_save_failed_is_saved_before_its_next_point() = runTest {
    val w = World()
    w.controller.load()
    w.failSaves = 1
    w.controller.start("箱根")
    assertTrue(w.rides.isEmpty())
    w.controller.onFix(fix(2_000, 35.0))
    assertEquals(RideStatus.RECORDING, w.stored().status)
    assertEquals(1, w.points.getValue("r1").size)
    assertFalse(w.controller.storeFailed.value)
  }

  @Test
  fun the_warning_stays_while_the_ride_is_still_unsaved() = runTest {
    val w = World()
    w.controller.load()
    w.failSaves = 2
    w.controller.start("箱根")
    w.now = 61_000
    // The heartbeat itself succeeds on a missing row; the ride is still not stored.
    w.controller.heartbeat()
    assertTrue(w.controller.storeFailed.value)
    w.controller.heartbeat()
    assertFalse(w.controller.storeFailed.value)
    assertEquals(RideStatus.RECORDING, w.stored().status)
  }

  @Test
  fun an_end_that_could_not_be_saved_is_saved_before_anything_else() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.failSaves = 1
    assertTrue(w.controller.end("箱根"))
    assertEquals(RideStatus.RECORDING, w.rides.getValue("r1").status)
    w.controller.start("御殿場")
    assertEquals(RideStatus.ENDED, w.rides.getValue("r1").status)
    assertEquals(RideStatus.RECORDING, w.rides.getValue("r2").status)
  }

  @Test
  fun a_failed_first_read_is_tried_again_before_a_new_ride_can_start() = runTest {
    val before = World()
    before.controller.load()
    before.controller.start("箱根")
    val after = World(now = 30_000).apply {
      rides += before.rides
      failActive = 1
    }
    after.controller.load()
    assertNull(after.controller.active.value)
    assertTrue(after.controller.storeFailed.value)
    // The next call reads again and finds the ride left recording, so no second one starts.
    assertFalse(after.controller.start("御殿場"))
    assertEquals("r1", after.controller.active.value?.id)
    assertEquals(1, after.rides.size)
    assertFalse(after.controller.storeFailed.value)
  }

  @Test
  fun a_retried_save_keeps_the_distance_recorded_meanwhile() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.controller.pause()
    w.failSaves = 3
    w.now = 9_000
    w.controller.resume()
    w.controller.onFix(fix(10_000, 35.000))
    w.controller.onFix(fix(15_000, 35.001))
    w.now = 70_000
    w.controller.heartbeat()
    assertEquals(RideStatus.RECORDING, w.stored().status)
    assertEquals(111.2, w.stored().distanceMeters, 0.5)
  }

  @Test
  fun a_pause_finishes_even_when_its_caller_is_cancelled() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.events.clear()
    w.gate = CompletableDeferred()
    val job = launch { w.controller.pause() }
    runCurrent()
    job.cancel()
    w.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(RideStatus.PAUSED, w.controller.active.value?.status)
    assertEquals(RideStatus.PAUSED, w.stored().status)
    assertEquals(listOf("alarm:cancel", "recording:false"), w.events)
  }

  @Test
  fun no_new_ride_starts_while_an_ended_one_is_still_unsaved() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.failSaves = 2
    w.controller.end("箱根")
    assertFalse(w.controller.start("御殿場"))
    assertEquals(setOf("r1"), w.rides.keys)
    assertTrue(w.controller.start("御殿場"))
    assertEquals(RideStatus.ENDED, w.rides.getValue("r1").status)
    assertEquals(RideStatus.RECORDING, w.rides.getValue("r2").status)
  }

  @Test
  fun a_failed_write_is_shown_and_the_ride_goes_on() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.failWrites = true
    w.controller.onFix(fix(2_000, 35.000))
    assertTrue(w.controller.storeFailed.value)
    assertEquals(RideStatus.RECORDING, w.controller.active.value?.status)
    w.failWrites = false
    w.controller.onFix(fix(7_000, 35.001))
    assertFalse(w.controller.storeFailed.value)
  }

  @Test
  fun every_saved_change_asks_for_a_send_and_a_failed_one_does_not() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 5_000
    w.controller.pause()
    w.now = 9_000
    w.controller.resume()
    w.now = 12_000
    w.controller.end("箱根")
    assertEquals(4, w.changes)
    w.failWrites = true
    w.controller.start("御殿場")
    assertEquals(4, w.changes)
  }

  @Test
  fun each_saved_point_is_reported_and_a_dropped_or_failed_one_is_not() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 6_000
    w.controller.onFix(fix(6_000, 35.0))
    w.controller.onFix(fix(7_000, 35.001, accuracy = 80.0))
    assertEquals(1, w.pointsAdded)
    w.failWrites = true
    w.controller.onFix(fix(8_000, 35.002))
    assertEquals(1, w.pointsAdded)
  }

  @Test
  fun pause_resume_and_start_say_whether_they_changed_anything() = runTest {
    val w = World()
    w.controller.load()
    assertFalse(w.controller.pause())
    assertFalse(w.controller.resume())
    assertTrue(w.controller.start("箱根"))
    assertFalse(w.controller.resume())
    w.now = 5_000
    assertTrue(w.controller.pause())
    assertFalse(w.controller.pause())
    w.now = 9_000
    assertTrue(w.controller.resume())
  }

  @Test
  fun a_pause_after_the_deadline_ends_the_ride_automatically_at_the_deadline() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 1_000 + MAX + 60_000
    assertFalse(w.controller.pause())
    val ended = w.stored()
    assertEquals(RideStatus.ENDED, ended.status)
    assertEquals(EndReason.AUTO, ended.endReason)
    assertEquals(1_000 + MAX, ended.endedAt())
    assertEquals(listOf(ended), w.autoEnded)
  }

  @Test
  fun an_end_after_the_deadline_is_an_automatic_end_at_the_deadline_with_the_new_name() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 1_000 + MAX + 60_000
    assertTrue(w.controller.end("箱根から御殿場へ"))
    val ended = w.stored()
    assertEquals(EndReason.AUTO, ended.endReason)
    assertEquals(1_000 + MAX, ended.endedAt())
    assertEquals("箱根から御殿場へ", ended.name)
    assertEquals(1, w.autoEnded.size)
  }

  @Test
  fun a_longer_or_shorter_limit_moves_the_recording_segments_deadline_and_its_alarm() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.events.clear()
    val updatedAt = w.stored().updatedAt
    w.now = 2 * HOUR_MS
    w.max = 6 * HOUR_MS
    w.controller.maxRecordingChanged()
    assertEquals(1_000 + 6 * HOUR_MS, w.stored().deadline)
    assertEquals(1_000 + 6 * HOUR_MS, w.controller.active.value?.deadline)
    assertEquals(listOf("alarm:${1_000 + 6 * HOUR_MS}"), w.events)
    // The deadline is not sent, so the Backend has nothing new.
    assertEquals(updatedAt, w.stored().updatedAt)
  }

  @Test
  fun a_limit_the_segment_has_already_passed_ends_the_ride_automatically_at_the_new_deadline() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 7 * HOUR_MS
    w.max = 6 * HOUR_MS
    w.controller.maxRecordingChanged()
    val ended = w.stored()
    assertEquals(EndReason.AUTO, ended.endReason)
    assertEquals(1_000 + 6 * HOUR_MS, ended.endedAt())
    assertEquals(1, w.autoEnded.size)
    assertNull(w.controller.active.value)
  }

  @Test
  fun a_paused_ride_takes_the_new_limit_when_it_resumes() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 5_000
    w.controller.pause()
    w.max = 6 * HOUR_MS
    w.controller.maxRecordingChanged()
    assertNull(w.stored().deadline)
    w.now = 9_000
    w.controller.resume()
    assertEquals(9_000 + 6 * HOUR_MS, w.stored().deadline)
  }

  // Android #36: each change works from the segment's start, and only the last one counts.
  @Test
  fun a_limit_changed_again_and_again_keeps_only_the_last_one() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 5 * HOUR_MS
    w.max = 6 * HOUR_MS
    w.controller.maxRecordingChanged()
    assertEquals(RideStatus.RECORDING, w.stored().status)
    w.max = 48 * HOUR_MS
    w.controller.maxRecordingChanged()
    assertEquals(1_000 + 48 * HOUR_MS, w.stored().deadline)
    assertEquals("alarm:${1_000 + 48 * HOUR_MS}", w.events.last())
  }

  @Test
  fun an_ended_ride_can_be_renamed_and_the_new_name_is_sent() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 5_000
    w.controller.end("箱根")
    val before = w.stored()
    val changes = w.changes
    w.now = 9_000
    assertTrue(w.controller.rename("r1", " 御殿場 "))
    assertEquals("御殿場", w.stored().name)
    assertEquals(9_000L, w.stored().updatedAt)
    assertEquals(before.segments, w.stored().segments)
    assertEquals(changes + 1, w.changes)
  }

  @Test
  fun renaming_the_active_ride_keeps_it_recording_under_the_new_name() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 5_000
    assertTrue(w.controller.rename("r1", "御殿場"))
    assertEquals("御殿場", w.controller.active.value?.name)
    assertEquals(RideStatus.RECORDING, w.stored().status)
  }

  @Test
  fun a_name_that_cannot_name_a_ride_or_a_ride_that_is_not_kept_is_not_renamed() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    assertFalse(w.controller.rename("r1", "  "))
    assertFalse(w.controller.rename("r9", "御殿場"))
    assertEquals("箱根", w.stored().name)
  }

  // Final review (Android #46): a new name the device could not write is reported, so SC-06 can say so.
  @Test
  fun a_rename_whose_save_fails_reports_it() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.now = 5_000
    w.controller.end("箱根")
    w.failSaves = 1
    assertFalse(w.controller.rename("r1", "御殿場"))
    assertEquals("箱根", w.stored().name)
  }

  @Test
  fun a_rename_of_the_active_ride_whose_save_fails_reports_it_and_keeps_the_name_to_save_later() = runTest {
    val w = World()
    w.controller.load()
    w.controller.start("箱根")
    w.failSaves = 1
    assertFalse(w.controller.rename("r1", "御殿場"))
    assertEquals("御殿場", w.controller.active.value?.name)
  }
}
