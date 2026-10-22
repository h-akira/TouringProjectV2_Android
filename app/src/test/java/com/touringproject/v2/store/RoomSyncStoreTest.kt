package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.api.ApiClient
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.pause
import com.touringproject.v2.sync.ChunkRange
import com.touringproject.v2.sync.PendingDeletion
import com.touringproject.v2.sync.UnsentAskMemo
import com.touringproject.v2.sync.RideSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val dao = db.rides()
  private val rides = RoomRideStore(dao)
  private val store = RoomSyncStore(dao, db.asks())
  private val ride = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS)

  @After
  fun close() = db.close()

  private suspend fun addPoints(count: Int) = repeat(count) {
    rides.addPoint("r1", RoutePoint(0, 2_000L + it, 35.0, 139.0, 5.0), 0.0, 2_000L + it)
  }

  @Test
  fun a_ride_is_unsent_until_its_meta_and_points_have_gone() = runBlocking {
    rides.save(ride)
    addPoints(5)
    assertEquals(listOf(Triple("r1", 0, 5)), store.unsent().map { Triple(it.ride.id, it.sentPoints, it.totalPoints) })
    store.rideSent("r1", ride.updatedAt)
    store.chunkSent("r1", ChunkRange(0, 0, 5), refused = false)
    assertEquals(emptyList<String>(), store.unsent().map { it.ride.id })
    addPoints(1)
    assertEquals(5, store.unsent().single().sentPoints)
  }

  @Test
  fun points_are_read_and_marked_in_the_order_they_were_recorded() = runBlocking {
    rides.save(ride)
    addPoints(7)
    assertEquals(listOf(2_003L, 2_004L), store.points("r1", 3, 5).map { it.t })
    store.chunkSent("r1", ChunkRange(0, 0, 3), refused = false)
    assertEquals(listOf(0, 0, 0, null, null, null, null), dao.points("r1").map { it.chunk })
  }

  @Test
  fun a_refused_version_is_not_unsent_but_a_newer_one_is() = runBlocking {
    rides.save(ride)
    store.rideRefused("r1", ride.updatedAt)
    assertEquals(emptyList<String>(), store.unsent().map { it.ride.id })
    rides.save(ride.end(5_000, EndReason.MANUAL, 5_000))
    assertEquals(listOf("r1"), store.unsent().map { it.ride.id })
  }

  @Test
  fun a_refused_chunk_marks_its_points_and_is_not_sent_again() = runBlocking {
    rides.save(ride)
    store.rideSent("r1", ride.updatedAt)
    addPoints(2)
    store.chunkSent("r1", ChunkRange(0, 0, 2), refused = true)
    assertEquals(listOf(true, true), dao.points("r1").map { it.refused })
    assertEquals(emptyList<String>(), store.unsent().map { it.ride.id })
  }

  // Android #27: the partial chunk sent at a pause was refused, the full one under the same seq is taken later.
  @Test
  fun a_chunk_taken_under_the_seq_of_a_refused_one_clears_the_refusal() = runBlocking {
    rides.save(ride)
    addPoints(2)
    store.chunkSent("r1", ChunkRange(0, 0, 2), refused = true)
    addPoints(1)
    store.chunkSent("r1", ChunkRange(0, 0, 3), refused = false)
    assertEquals(listOf(false, false, false), dao.points("r1").map { it.refused })
  }

  @Test
  fun an_ended_ride_reaches_the_backend_and_the_next_pass_sends_nothing() = runBlocking {
    val server = MockWebServer().apply { start() }
    repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
    val sync = RideSync(store, { ApiClient(server.url("/Prod/").toString(), "key-1", "rider01", OkHttpClient()) }) { _, _ -> }
    rides.save(ride)
    addPoints(350)
    rides.save(ride.end(5_000, EndReason.MANUAL, 5_000))
    sync.pass()
    val paths = (1..3).map { server.takeRequest() }
    assertEquals(listOf("/Prod/rides/r1", "/Prod/rides/r1/route/0", "/Prod/rides/r1/route/1"), paths.map { it.path })
    val last = Json.parseToJsonElement(paths[2].body.readUtf8()).jsonObject["points"]!!.jsonArray
    assertEquals(50, last.size)
    sync.pass()
    assertEquals(3, server.requestCount)
    server.shutdown()
  }

  private suspend fun keepAsk(askId: String) =
    db.asks().upsert(AskEntity(askId, 1_000, 2_000, "Q", "A", 35.0, 139.0, null, null, "voice", "c1", true, "answered", null, "r1"))

  @Test
  fun a_deletion_waits_until_it_is_sent() = runBlocking {
    rides.save(ride.end(5_000, EndReason.MANUAL, 5_000))
    keepAsk("a1")
    db.asks().delete("a1", 6_000)
    rides.delete("r1", 7_000)
    assertEquals(listOf(PendingDeletion("ask", "a1", "r1"), PendingDeletion("ride", "r1", null)), store.deletions())
    store.deletionSent(PendingDeletion("ask", "a1", "r1"))
    assertEquals(listOf("r1"), store.deletions().map { it.id })
  }

  @Test
  fun a_memo_is_unsent_until_its_version_is_taken_or_refused() = runBlocking {
    keepAsk("a1")
    keepAsk("a2")
    assertEquals(emptyList<UnsentAskMemo>(), store.unsentAskMemos())
    db.asks().writeMemo("a1", "また来たい", 5_000)
    db.asks().writeMemo("a2", "給油", 6_000)
    assertEquals(listOf(UnsentAskMemo("a1", "また来たい", 5_000), UnsentAskMemo("a2", "給油", 6_000)), store.unsentAskMemos())
    store.askMemoSent("a1", 5_000)
    store.askMemoRefused("a2", 6_000)
    assertEquals(emptyList<UnsentAskMemo>(), store.unsentAskMemos())
    db.asks().writeMemo("a1", null, 7_000)
    assertEquals(listOf(UnsentAskMemo("a1", null, 7_000)), store.unsentAskMemos())
  }

  // Android #23: the ride and its segments come from one read, so they are of one version.
  @Test
  fun an_unsent_ride_comes_with_its_segments_and_counts_read_together() = runBlocking {
    val paused = ride.pause(5_000)
    rides.save(paused)
    addPoints(3)
    store.chunkSent("r1", ChunkRange(0, 0, 2), refused = false)
    val unsent = store.unsent().single()
    assertEquals(paused.status, unsent.ride.status)
    assertEquals(paused.segments, unsent.ride.segments)
    assertEquals(2, unsent.sentPoints)
    assertEquals(3, unsent.totalPoints)
  }
}
