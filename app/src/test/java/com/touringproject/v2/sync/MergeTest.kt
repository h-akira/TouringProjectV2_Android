package com.touringproject.v2.sync

import com.touringproject.v2.api.CloudAsk
import com.touringproject.v2.api.CloudDeleted
import com.touringproject.v2.api.CloudNote
import com.touringproject.v2.api.CloudRide
import com.touringproject.v2.api.CloudRoute
import com.touringproject.v2.api.Point
import com.touringproject.v2.api.PointBody
import com.touringproject.v2.api.SegmentBody
import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.RideStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = "2026-10-22T01:00:00Z"
private val T0_MS = epochMs(T0)
private const val NOW = 1_800_000_000_000L

private fun iso(offsetMs: Long) = java.time.Instant.ofEpochMilli(T0_MS + offsetMs).toString()

private fun ride(id: String = "r1", status: String = "ended", updated: Long = 3_600_000, ended: Long? = 3_600_000) =
  CloudRide(id, "箱根", status, listOf(SegmentBody(T0, ended?.let(::iso))), false, iso(updated), null)

private fun route(rideId: String = "r1", seq: Int = 0, count: Int = 3, from: Long = 1_000) =
  CloudRoute(rideId, seq, (0 until count).map { PointBody(iso(from + it * 1_000L), 35.0, 139.0, 5.0, 0) })

private fun note(id: String = "n1", rideId: String = "r1", updated: Long = 600_000) =
  CloudNote(rideId, id, iso(600_000), Point(35.1, 139.1), "展望", iso(updated), "箱根町仙石原", 90.0)

private fun ask(
  id: String = "a1",
  rideId: String? = "r1",
  status: String = "completed",
  memoUpdated: Long? = null,
  updated: Long? = null,
  error: String? = null,
  answer: String? = "金時山です",
) = CloudAsk(
  id, status, iso(1_200_000), null, "右手の山は", answer, error, null, Point(35.2, 139.2), "箱根町", 12.5, "audio",
  if (status == "offline") null else "c1", true, rideId, memoUpdated?.let { "メモ" }, memoUpdated?.let(::iso), updated?.let(::iso),
)

class MergeTest {
  // Slice 4 spec §6.1: what only the cloud has comes in, marked as what the Backend already has.
  @Test
  fun what_only_the_cloud_has_comes_in_as_sent() {
    val merged = merge(DeviceRecords(), listOf(ride(), route(), note(), ask()), NOW)
    val taken = merged.rides.single()
    assertEquals(T0_MS + 3_600_000, taken.ride.updatedAt)
    assertEquals(taken.ride.updatedAt, taken.sentUpdatedAt)
    assertEquals(EndReason.MANUAL, taken.ride.endReason)
    assertEquals(listOf(0, 0, 0), merged.routes.single().points.map { it.chunk })
    assertEquals(T0_MS + 1_000, merged.routes.single().points.first().point.t)
    assertEquals("箱根町仙石原", merged.notes.single().address)
    assertEquals(AskOutcome.ANSWERED, merged.asks.single().ask.outcome)
    assertEquals(3, merged.count)
  }

  @Test
  fun the_newer_version_wins() {
    val device = DeviceRecords(
      rides = mapOf("r1" to KeptRide(T0_MS + 9_000_000, 3)),
      notes = mapOf("n1" to T0_MS + 100_000),
      asks = mapOf("u1" to KeptAsk(T0_MS + 9_000_000, null), "u2" to KeptAsk(T0_MS + 100_000, null)),
    )
    val cloud = listOf(ride(), route(), note(), ask("u1", status = "offline", updated = 1_200_000), ask("u2", status = "offline", updated = 1_200_000))
    val merged = merge(device, cloud, NOW)
    assertEquals(emptyList<TakenRide>(), merged.rides)
    assertEquals(emptyList<TakenRoute>(), merged.routes)
    assertEquals(listOf("n1"), merged.notes.map { it.id })
    assertEquals(listOf("u2"), merged.asks.map { it.ask.askId })
    assertEquals(2, merged.count)
  }

  // A Backend's ask always takes the cloud's answer; its memo goes by its own time.
  @Test
  fun a_memo_is_compared_on_its_own() {
    val device = DeviceRecords(
      rides = mapOf("r1" to KeptRide(T0_MS, 0)),
      asks = mapOf("a1" to KeptAsk(null, T0_MS + 9_000_000), "a2" to KeptAsk(null, T0_MS)),
    )
    val merged = merge(device, listOf(ask("a1", memoUpdated = 1_500_000), ask("a2", memoUpdated = 1_500_000), ask("a3")), NOW)
    assertEquals(
      listOf(Triple("a1", true, false), Triple("a2", true, true), Triple("a3", true, false)),
      merged.asks.map { Triple(it.ask.askId, it.content, it.memo) },
    )
    assertEquals(T0_MS + 1_500_000, merged.asks[1].memoUpdatedAt)
    assertEquals(2, merged.count)
  }

  // Slice 3 spec §4.2: a deletion's mark removes the record, and a ride all that is under it.
  @Test
  fun a_mark_in_the_cloud_removes_what_the_device_keeps() {
    val device = DeviceRecords(
      rides = mapOf("r1" to KeptRide(T0_MS, 3), "r2" to KeptRide(T0_MS, 0)),
      notes = mapOf("n1" to T0_MS),
      asks = mapOf("a1" to KeptAsk(null, null)),
    )
    val cloud = listOf(
      CloudDeleted("ride", "r1", null, iso(5_000_000)),
      CloudDeleted("note", "n1", "r2", iso(5_000_000)),
      CloudDeleted("ask", "a1", "r2", iso(5_000_000)),
      CloudDeleted("ask", "a9", "r2", iso(5_000_000)),
      ride("r1"), route("r1"), note("n5", "r1"), ask("a5", "r1"),
    )
    val merged = merge(device, cloud, NOW)
    assertEquals(GoneRecords(setOf("r1"), setOf("a1"), setOf("n1")), merged.gone)
    assertTrue(merged.rides.isEmpty() && merged.routes.isEmpty() && merged.notes.isEmpty() && merged.asks.isEmpty())
    assertEquals(3, merged.count)
  }

  // Android #77: an ask or point memo marked with its ride goes with the ride, and is counted once, as the ride.
  @Test
  fun what_goes_with_its_ride_is_counted_once() {
    val device = DeviceRecords(
      rides = mapOf("r1" to KeptRide(T0_MS, 3), "r2" to KeptRide(T0_MS, 0)),
      notes = mapOf("n1" to T0_MS, "n2" to T0_MS),
      asks = mapOf("a1" to KeptAsk(null, null)),
      rideOf = mapOf("n1" to "r1", "a1" to "r1", "n2" to "r2"),
    )
    val cloud = listOf(
      CloudDeleted("ask", "a1", "r1", iso(4_000_000)),
      CloudDeleted("note", "n1", "r1", iso(4_000_000)),
      CloudDeleted("ride", "r1", null, iso(5_000_000)),
      CloudDeleted("note", "n2", "r2", iso(5_000_000)),
    )
    val merged = merge(device, cloud, NOW)
    assertEquals(GoneRecords(setOf("r1"), setOf("a1"), setOf("n1", "n2")), merged.gone)
    assertEquals(2, merged.count)
  }

  // A record deleted on the device, its deletion not yet sent, stays deleted.
  @Test
  fun the_devices_own_deletions_keep_the_cloud_copy_out() {
    val device = DeviceRecords(deletions = setOf("ride" to "r1", "note" to "n2", "ask" to "a2"))
    val cloud = listOf(ride("r1"), route("r1"), note("n1", "r1"), ask("a1", "r1"), ride("r2"), note("n2", "r2"), ask("a2", "r2"))
    val merged = merge(device, cloud, NOW)
    assertEquals(listOf("r2"), merged.rides.map { it.ride.id })
    assertEquals(emptyList<TakenRoute>(), merged.routes)
    assertEquals(emptyList<TakenNote>(), merged.notes)
    assertEquals(emptyList<TakenAsk>(), merged.asks)
  }

  // Slice 4 spec §6.1: a ride left recording ends at its last point, and its end is left for the next pass to send.
  @Test
  fun a_ride_left_recording_ends_at_its_last_point_and_is_left_to_send() {
    val merged = merge(DeviceRecords(), listOf(ride(status = "recording", updated = 2_000, ended = null), route(count = 3, from = 1_000)), NOW)
    val taken = merged.rides.single()
    assertEquals(RideStatus.ENDED, taken.ride.status)
    assertEquals(EndReason.SHUTDOWN, taken.ride.endReason)
    assertEquals(T0_MS + 3_000, taken.ride.segments.single().endedAt)
    assertEquals(T0_MS + 2_000, taken.sentUpdatedAt)
    assertTrue(taken.ride.updatedAt > taken.sentUpdatedAt)
  }

  @Test
  fun a_ride_left_recording_without_a_point_ends_where_its_segment_started() {
    val taken = merge(DeviceRecords(), listOf(ride(status = "recording", updated = 0, ended = null)), NOW).rides.single()
    assertEquals(T0_MS, taken.ride.segments.single().endedAt)
  }

  // adr/0008: no record comes in without its ride, in the cloud or on the device.
  @Test
  fun records_without_their_ride_are_left_out() {
    val device = DeviceRecords(rides = mapOf("r2" to KeptRide(T0_MS + 9_000_000, 0)))
    val cloud = listOf(route("r9"), note("n1", "r9"), ask("a1", "r9"), ask("a2", null), note("n2", "r2"), ask("a3", "r2"))
    val merged = merge(device, cloud, NOW)
    assertEquals(emptyList<TakenRoute>(), merged.routes)
    assertEquals(listOf("n2"), merged.notes.map { it.id })
    assertEquals(listOf("a3"), merged.asks.map { it.ask.askId })
  }

  // A route only grows: the cloud's goes in only when it has more points, in chunk order.
  @Test
  fun the_longer_route_wins() {
    val cloud = listOf(ride(), route(seq = 1, count = 2, from = 400_000), route(seq = 0, count = 300))
    val shorter = merge(DeviceRecords(rides = mapOf("r1" to KeptRide(T0_MS + 9_000_000, 100))), cloud, NOW)
    assertEquals(listOf(0 to 300, 1 to 2), shorter.routes.single().points.groupBy { it.chunk }.map { (k, v) -> k to v.size })
    val longer = merge(DeviceRecords(rides = mapOf("r1" to KeptRide(T0_MS + 9_000_000, 302))), cloud, NOW)
    assertEquals(emptyList<TakenRoute>(), longer.routes)
  }

  @Test
  fun an_ask_comes_in_as_the_ask_loop_keeps_it() {
    val heard = ask(status = "failed", error = "tts_failed").toRecord()!!
    assertEquals(AskOutcome.ANSWERED to null, heard.outcome to heard.failure)
    val unheard = ask(status = "failed", error = "no_speech", answer = null).toRecord()!!
    assertEquals(AskOutcome.FAILED to "no_speech", unheard.outcome to unheard.failure)
    // Slice 4 spec §6.1 (2026-10-22): an ask still waiting has no result, and comes in as a failure without one.
    val waiting = ask(status = "processing", answer = null).toRecord()!!
    assertEquals(AskOutcome.FAILED to "timeout", waiting.outcome to waiting.failure)
    val text = ask().copy(inputType = "text", question = "近くのカフェ", transcript = null).toRecord()!!
    assertEquals("text" to "近くのカフェ", text.input to text.transcript)
    val unreached = ask(status = "offline", updated = 1_300_000, answer = null).toRecord()!!
    assertEquals(AskOutcome.OFFLINE, unreached.outcome)
    assertNull(unreached.conversationId)
    assertEquals(T0_MS + 1_300_000, unreached.updatedAt)
    assertTrue(unreached.sent)
    assertNull(ask().copy(askedAt = null).toRecord())
  }
}
