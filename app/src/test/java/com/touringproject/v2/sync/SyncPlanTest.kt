package com.touringproject.v2.sync

import com.touringproject.v2.api.HeadingSample
import com.touringproject.v2.api.Point
import com.touringproject.v2.api.SegmentBody
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.pause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
  @Test
  fun while_recording_only_full_chunks_go() {
    assertEquals(emptyList<ChunkRange>(), chunksToSend(0, 0, false))
    assertEquals(emptyList<ChunkRange>(), chunksToSend(0, 299, false))
    assertEquals(listOf(ChunkRange(0, 0, 300), ChunkRange(1, 300, 600)), chunksToSend(0, 650, false))
    assertEquals(listOf(ChunkRange(2, 600, 900)), chunksToSend(600, 900, false))
  }

  @Test
  fun once_paused_or_ended_the_partial_chunk_goes_too() {
    assertEquals(listOf(ChunkRange(0, 0, 300), ChunkRange(1, 300, 350)), chunksToSend(0, 350, true))
  }

  @Test
  fun a_partial_chunk_that_went_goes_again_only_when_it_grows() {
    assertEquals(emptyList<ChunkRange>(), chunksToSend(120, 120, true))
    assertEquals(emptyList<ChunkRange>(), chunksToSend(120, 250, false))
    assertEquals(listOf(ChunkRange(0, 0, 300)), chunksToSend(120, 350, false))
    assertEquals(listOf(ChunkRange(0, 0, 250)), chunksToSend(120, 250, true))
  }

  @Test
  fun the_meta_goes_when_it_changed_unless_this_version_was_refused() {
    assertTrue(metaToSend(1_000, null, null))
    assertFalse(metaToSend(1_000, 1_000, null))
    assertTrue(metaToSend(2_000, 1_000, null))
    assertFalse(metaToSend(2_000, 1_000, 2_000))
    assertTrue(metaToSend(3_000, 1_000, 2_000))
  }

  @Test
  fun a_ride_is_sent_in_utc_with_its_open_segment_as_null_and_only_an_auto_end_as_autoEnded() {
    val ride = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS)
    val body = ride.toBody()
    assertEquals("recording", body.status)
    assertEquals(listOf(SegmentBody("1970-01-01T00:00:01Z", null)), body.segments)
    assertEquals("1970-01-01T00:00:01Z", body.updatedAt)
    assertFalse(body.autoEnded)
    assertEquals("paused", ride.pause(2_500).toBody().status)
    assertTrue(ride.end(5_000, EndReason.AUTO, 6_000).toBody().autoEnded)
    assertFalse(ride.end(5_000, EndReason.SHUTDOWN, 6_000).toBody().autoEnded)
    assertEquals("ended", ride.end(5_000, EndReason.SHUTDOWN, 6_000).toBody().status)
  }

  @Test
  fun a_point_keeps_its_segment_and_milliseconds() {
    val body = RoutePoint(2, 1_234, 35.0, 139.0, null).toBody()
    assertEquals("1970-01-01T00:00:01.234Z", body.t)
    assertEquals(2, body.segment)
  }

  // Slice 3 spec §6.2: a memo without a place goes without one, and a heading sample goes only when it is whole.
  @Test
  fun a_point_memo_goes_with_its_place_and_sample_in_utc() {
    val note = UnsentNote("n1", "r1", 1_000, 35.3, 138.9, 35.29, 138.89, 500, "展望スペース", 2_000)
    val body = note.toBody()
    assertEquals("1970-01-01T00:00:01Z", body.at)
    assertEquals(Point(35.3, 138.9), body.location)
    assertEquals(HeadingSample(35.29, 138.89, "1970-01-01T00:00:00.500Z"), body.headingSample)
    assertEquals("展望スペース" to "1970-01-01T00:00:02Z", body.text to body.updatedAt)
    val bare = note.copy(latitude = null, longitude = null, sampleAt = null).toBody()
    assertNull(bare.location)
    assertNull(bare.headingSample)
  }

  // Slice 5 spec §4.4: an unreached ask goes with the device's heading; one kept before slice 5 still has its sample.
  @Test
  fun an_unreached_ask_goes_with_its_heading_and_an_old_one_with_its_sample() {
    val ask = UnsentUnreached("u1", "r1", 1_000, 35.3, 138.9, null, null, null, 2_000, 271.5)
    val body = ask.toBody()
    assertEquals(271.5, body.headingDegrees!!, 0.0)
    assertNull(body.headingSample)
    val old = UnsentUnreached("u0", "r1", 1_000, 35.3, 138.9, 35.29, 138.89, 500, 2_000).toBody()
    assertNull(old.headingDegrees)
    assertEquals(HeadingSample(35.29, 138.89, "1970-01-01T00:00:00.500Z"), old.headingSample)
  }
}
