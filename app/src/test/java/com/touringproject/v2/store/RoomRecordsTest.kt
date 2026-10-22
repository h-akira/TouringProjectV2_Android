package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.records.SaveState
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.pause
import com.touringproject.v2.ride.resume
import com.touringproject.v2.sync.ChunkRange
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRecordsTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val rides = RoomRideStore(db.rides())
  private val sync = RoomSyncStore(db.rides(), db.asks())
  private val log = RoomAskLog(db.asks(), MainScope()) { _, _ -> }
  private val records = RoomRecords(db.rides(), db.asks(), db.notes())

  @After
  fun close() = db.close()

  private fun ask(id: String, at: Long, rideId: String?) = AskRecord(
    id, at, at + 1, "右手の山は？", "金時山です。", 35.2, 139.0, "神奈川県箱根町", 45.0, "voice", "c1", false, AskOutcome.ANSWERED, null, rideId,
  )

  @Test
  fun the_list_counts_each_rides_asks_and_tells_how_far_it_was_sent() = runBlocking {
    val ended = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS).pause(2_000).resume(3_000, 7, 24 * HOUR_MS).end(4_000, EndReason.MANUAL, 4_000)
    rides.save(ended)
    rides.addPoint("r1", RoutePoint(0, 1_500, 35.0, 139.0, 5.0), 0.0, 1_500)
    log.save(ask("a1", 1_200, "r1"))
    log.save(ask("a2", 3_500, "r1"))
    log.save(ask("a3", 2_500, null))
    val active = newRide("r2", "御殿場", 5_000, 7, 24 * HOUR_MS)
    rides.save(active)
    var list = records.list.first()
    assertEquals(listOf("r2", "r1"), list.map { it.ride.id })
    assertEquals(listOf(0, 2), list.map { it.questions })
    assertEquals(listOf(SaveState.RECORDING, SaveState.UNSENT), list.map { it.saveState })
    assertEquals(2, list[1].ride.segments.size)
    sync.rideSent("r1", ended.updatedAt)
    sync.chunkSent("r1", ChunkRange(0, 0, 1), refused = false)
    list = records.list.first()
    assertEquals(SaveState.SAVED, list[1].saveState)
  }

  // Android #27: a refused chunk keeps the ride "未送信あり" until the same seq is taken.
  @Test
  fun a_refused_chunk_keeps_the_ride_unsent_until_its_seq_is_taken() = runBlocking {
    val ended = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS).end(4_000, EndReason.MANUAL, 4_000)
    rides.save(ended)
    sync.rideSent("r1", ended.updatedAt)
    rides.addPoint("r1", RoutePoint(0, 1_500, 35.0, 139.0, 5.0), 0.0, 1_500)
    sync.chunkSent("r1", ChunkRange(0, 0, 1), refused = true)
    assertEquals(SaveState.UNSENT, records.list.first().single().saveState)
    sync.chunkSent("r1", ChunkRange(0, 0, 1), refused = false)
    assertEquals(SaveState.SAVED, records.list.first().single().saveState)
  }

  @Test
  fun a_ride_its_asks_and_its_route_are_read_for_the_detail() = runBlocking {
    val ride = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS)
    rides.save(ride)
    rides.addPoint("r1", RoutePoint(0, 1_500, 35.0, 139.0, 5.0), 0.0, 1_500)
    log.save(ask("a2", 3_500, "r1"))
    log.save(ask("a1", 1_200, "r1"))
    assertEquals(ride.copy(lastAliveAt = 1_500), records.ride("r1").first())
    assertEquals(listOf("a1", "a2"), records.asks("r1").first().map { it.askId })
    assertEquals(listOf(1_500L), records.route("r1").map { it.t })
    assertNull(records.ride("r9").first())
  }

  // Slice 3 spec §5.3: a memo of one of the ride's asks not yet taken leaves the ride "未送信あり".
  @Test
  fun a_memo_not_yet_sent_leaves_the_ride_unsent() = runBlocking {
    val ended = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS).end(4_000, EndReason.MANUAL, 4_000)
    rides.save(ended)
    log.save(ask("a1", 1_200, "r1"))
    sync.rideSent("r1", ended.updatedAt)
    assertEquals(SaveState.SAVED, records.list.first().single().saveState)
    db.asks().writeMemo("a1", "また来たい", 5_000)
    assertEquals(SaveState.UNSENT, records.list.first().single().saveState)
    assertEquals("また来たい", records.asks("r1").first().single().memo)
    sync.askMemoSent("a1", 5_000)
    assertEquals(SaveState.SAVED, records.list.first().single().saveState)
  }
}
