package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.api.CloudAsk
import com.touringproject.v2.api.CloudDeleted
import com.touringproject.v2.api.CloudNote
import com.touringproject.v2.api.CloudRide
import com.touringproject.v2.api.CloudRoute
import com.touringproject.v2.api.Point
import com.touringproject.v2.api.PointBody
import com.touringproject.v2.api.SegmentBody
import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.records.SaveState
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.withMemo
import com.touringproject.v2.sync.ChunkRange
import com.touringproject.v2.sync.epochMs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val T0 = "2026-10-22T01:00:00Z"
private val T0_MS = epochMs(T0)
private const val NOW = 1_800_000_000_000L

private fun iso(offsetMs: Long) = java.time.Instant.ofEpochMilli(T0_MS + offsetMs).toString()

@RunWith(AndroidJUnit4::class)
class RoomTakeoverTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val rides = RoomRideStore(db.rides())
  private val sync = RoomSyncStore(db.rides(), db.asks(), db.notes())
  private val log = RoomAskLog(db.asks(), MainScope()) { _, _ -> }
  private val records = RoomRecords(db.rides(), db.asks(), db.notes())
  private val takeover = RoomTakeover(db)

  @After
  fun close() = db.close()

  private fun cloudRide(id: String = "r1", status: String = "ended", ended: String? = iso(3_600_000)) =
    CloudRide(id, "箱根", status, listOf(SegmentBody(T0, ended)), false, iso(3_600_000), null)

  private fun cloudRoute(id: String = "r1") =
    CloudRoute(id, 0, (0 until 3).map { PointBody(iso(1_000L + it * 60_000), 35.0 + it * 0.01, 139.0, 5.0, 0) })

  private fun cloudAsk(id: String = "a1", rideId: String = "r1", memoUpdated: Long? = null) = CloudAsk(
    id, "completed", iso(1_200_000), null, "右手の山は", "金時山です", null, iso(1_210_000), Point(35.2, 139.2), "箱根町", 12.5,
    "audio", "c1", true, rideId, memoUpdated?.let { "クラウドのメモ" }, memoUpdated?.let(::iso), null,
  )

  private val cloud = listOf(
    cloudRide(), cloudRoute(),
    CloudNote("r1", "n1", iso(600_000), Point(35.1, 139.1), "展望", iso(600_000), "箱根町仙石原", 90.0),
    cloudAsk(),
    CloudAsk("u1", "offline", iso(1_300_000), rideId = "r1", updatedAt = iso(1_300_000)),
  )

  private fun deviceAsk(id: String, rideId: String) =
    AskRecord(id, 1_200, 1_201, "質問", "回答", 35.0, 139.0, "箱根町", 45.0, "voice", "c0", false, AskOutcome.ANSWERED, null, rideId)

  /** A ride the device recorded and sent whole: nothing of it is left to send. */
  private suspend fun sentRide(id: String) {
    val ride = newRide(id, "御殿場", 1_000, 7, 24 * HOUR_MS).end(4_000, EndReason.MANUAL, 4_000)
    rides.save(ride)
    rides.addPoint(id, RoutePoint(0, 1_500, 35.0, 139.0, 5.0), 0.0, 1_500)
    sync.rideSent(id, ride.updatedAt)
    sync.chunkSent(id, ChunkRange(0, 0, 1), refused = false)
  }

  // Slice 4 spec §6.1: what comes in is marked as sent, so none of it is sent back.
  @Test
  fun the_clouds_records_come_in_and_are_not_sent_back() = runBlocking {
    assertEquals(4, takeover.take(cloud, replace = false, now = NOW))
    assertEquals(0, records.summary.first().unsent)
    assertEquals(SaveState.SAVED, records.list.first().single().saveState)
    assertEquals(emptyList<String>(), sync.unsent().map { it.ride.id })
    assertTrue(records.ride("r1").first()!!.distanceMeters > 2_000)
    assertEquals(listOf("a1", "u1"), records.asks("r1").first().map { it.askId })
    assertEquals("箱根町仙石原", records.notes("r1").first().single().address)
    assertEquals(3, records.route("r1").size)
  }

  @Test
  fun a_ride_left_recording_comes_in_ended_and_goes_back_ended() = runBlocking {
    takeover.take(listOf(cloudRide(status = "recording", ended = null), cloudRoute()), replace = false, now = NOW)
    val unsent = sync.unsent().single()
    assertEquals(RideStatus.ENDED, unsent.ride.status)
    assertEquals(EndReason.SHUTDOWN, unsent.ride.endReason)
    assertEquals(3 to 3, unsent.sentPoints to unsent.totalPoints)
    assertNull(rides.active())
  }

  // Slice 4 spec §6.4: another user name's records replace all the device keeps.
  @Test
  fun replacing_removes_the_devices_records_first() = runBlocking {
    sentRide("r0")
    log.save(deviceAsk("a0", "r0"))
    assertEquals(4, takeover.take(cloud, replace = true, now = NOW))
    assertEquals(listOf("r1"), records.list.first().map { it.ride.id })
    assertEquals(emptyList<AskRecord>(), records.asks("r0").first())
    assertEquals(2, records.summary.first().asks)
  }

  // US-E-06.008: with something left to send, or a ride recording, nothing changes.
  @Test
  fun nothing_changes_while_something_is_left_to_send_or_a_ride_records() = runBlocking {
    rides.save(newRide("r0", "御殿場", 1_000, 7, 24 * HOUR_MS).end(4_000, EndReason.MANUAL, 4_000))
    assertNull(takeover.take(cloud, replace = true, now = NOW))
    assertEquals(listOf("r0"), records.list.first().map { it.ride.id })
    rides.save(newRide("r2", "伊豆", 5_000, 7, 24 * HOUR_MS))
    assertNull(takeover.take(cloud, replace = false, now = NOW))
    assertEquals(setOf("r0", "r2"), records.list.first().map { it.ride.id }.toSet())
  }

  // Slice 4 spec §8: a record that cannot be read fails the whole take, after the device's records were cleared.
  @Test
  fun a_failure_midway_leaves_the_device_as_it_was() = runBlocking {
    sentRide("r0")
    val broken = cloud + cloudRide("r5", status = "flying")
    assertTrue(runCatching { takeover.take(broken, replace = true, now = NOW) }.isFailure)
    assertEquals(listOf("r0"), records.list.first().map { it.ride.id })
    assertEquals(1, records.route("r0").size)
  }

  // SC-14's read again: the device's newer edits stay, waiting to be sent, and the cloud's marks remove records.
  @Test
  fun reading_again_keeps_the_devices_newer_edits_and_follows_the_clouds_marks() = runBlocking {
    takeover.take(cloud, replace = false, now = NOW)
    val kept = rides.ride("r1")!!
    rides.save(kept.withMemo("端末のメモ", NOW))
    db.asks().writeMemo("a1", "端末の会話メモ", NOW)
    val again = listOf(cloudRide(), cloudRoute(), cloudAsk(memoUpdated = 1_500_000), CloudDeleted("note", "n1", "r1", iso(5_000_000)))
    assertEquals(1, takeover.take(again, replace = false, now = NOW + 1_000))
    assertEquals("端末のメモ", records.ride("r1").first()!!.memo)
    assertEquals("端末の会話メモ", records.asks("r1").first().first { it.askId == "a1" }.memo)
    assertEquals(emptyList<Any>(), records.notes("r1").first())
    assertEquals(1, records.summary.first().unsentRides)
    assertEquals(1, records.summary.first().memos)
  }

  @Test
  fun a_newer_memo_in_the_cloud_replaces_the_devices() = runBlocking {
    takeover.take(listOf(cloudRide(), cloudAsk()), replace = false, now = NOW)
    takeover.take(listOf(cloudRide(), cloudAsk(memoUpdated = 1_500_000)), replace = false, now = NOW)
    val ask = records.asks("r1").first().single()
    assertEquals("クラウドのメモ", ask.memo)
    assertEquals(0, records.summary.first().memos)
  }
}
