package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.ask.ASK_INPUT_VOICE
import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.ask.unreachedAsk
import com.touringproject.v2.api.HeadingSample
import com.touringproject.v2.location.Fix
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RoomAskLogTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val errors = mutableListOf<String>()
  private val log = RoomAskLog(db.asks(), kotlinx.coroutines.MainScope()) { message, _ -> errors += message }
  private val record = AskRecord(
    "a1", 1_000, 9_000, "Q", "A", 35.0, 139.0, "御殿場市", 90.0, ASK_INPUT_VOICE, "c1", true, AskOutcome.ANSWERED, null, "r1",
  )

  private fun keepRide(id: String = "r1") = runBlocking { RoomRideStore(db.rides()).save(newRide(id, "箱根", 500, 7, 24 * HOUR_MS)) }

  @Test
  fun a_kept_ask_is_stored_with_its_ride_and_outcome() = runBlocking {
    keepRide()
    log.save(record)
    val stored = db.asks().ask("a1")!!
    assertEquals("answered", stored.status)
    assertEquals("r1", stored.rideId)
    assertTrue(stored.newConversation)
    assertFalse(log.failed.value)
    db.close()
  }

  @Test
  fun the_count_of_a_ride_follows_the_asks_kept_for_it() = runBlocking {
    keepRide()
    keepRide("r2")
    val count = db.asks().countForRide("r1")
    assertEquals(0, count.first())
    log.save(record)
    log.save(record.copy(askId = "a2", outcome = AskOutcome.FAILED, failure = "no_speech"))
    log.save(record.copy(askId = "a3", rideId = null))
    log.save(record.copy(askId = "a4", rideId = "r2"))
    assertEquals(2, count.first())
    db.close()
  }

  @Test
  fun a_failed_write_is_logged_and_shown_until_the_next_one_works() = runBlocking {
    var full = true
    val dao = object : AskDao {
      override suspend fun upsert(ask: AskEntity) = if (full) throw IOException("disk full") else Unit
      override suspend fun ask(askId: String): AskEntity? = null
      override suspend fun rideKept(rideId: String) = true
      override fun countForRide(rideId: String) = kotlinx.coroutines.flow.flowOf(0)
      override fun forRide(rideId: String) = kotlinx.coroutines.flow.flowOf(emptyList<AskEntity>())
      override suspend fun setMemo(askId: String, memo: String?, at: Long) = error("not used")
      override suspend fun deleteRow(askId: String) = error("not used")
      override suspend fun markDeleted(deletion: DeletionEntity) = error("not used")
      override suspend fun unsentMemos(): List<AskMemoRow> = error("not used")
      override suspend fun markMemoSent(askId: String, updatedAt: Long) = error("not used")
      override suspend fun markMemoRefused(askId: String, updatedAt: Long) = error("not used")
      override suspend fun unsentUnreached(): List<AskEntity> = error("not used")
      override suspend fun markUnreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?) = error("not used")
      override suspend fun markUnreachedRefused(askId: String, updatedAt: Long) = error("not used")
    }
    val failing = RoomAskLog(dao, kotlinx.coroutines.MainScope()) { message, _ -> errors += message }
    failing.save(record)
    assertTrue(failing.failed.value)
    assertEquals(1, errors.size)
    full = false
    failing.save(record)
    assertFalse(failing.failed.value)
    db.close()
  }

  // Android #28: the rider closes the band; the warning goes until another ask cannot be written.
  @Test
  fun the_warning_goes_when_the_rider_closes_it() = runBlocking {
    val dao = object : AskDao {
      override suspend fun upsert(ask: AskEntity) = throw IOException("disk full")
      override suspend fun ask(askId: String): AskEntity? = null
      override suspend fun rideKept(rideId: String) = true
      override fun countForRide(rideId: String) = kotlinx.coroutines.flow.flowOf(0)
      override fun forRide(rideId: String) = kotlinx.coroutines.flow.flowOf(emptyList<AskEntity>())
      override suspend fun setMemo(askId: String, memo: String?, at: Long) = error("not used")
      override suspend fun deleteRow(askId: String) = error("not used")
      override suspend fun markDeleted(deletion: DeletionEntity) = error("not used")
      override suspend fun unsentMemos(): List<AskMemoRow> = error("not used")
      override suspend fun markMemoSent(askId: String, updatedAt: Long) = error("not used")
      override suspend fun markMemoRefused(askId: String, updatedAt: Long) = error("not used")
      override suspend fun unsentUnreached(): List<AskEntity> = error("not used")
      override suspend fun markUnreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?) = error("not used")
      override suspend fun markUnreachedRefused(askId: String, updatedAt: Long) = error("not used")
    }
    val failing = RoomAskLog(dao, kotlinx.coroutines.MainScope()) { message, _ -> errors += message }
    failing.save(record)
    assertTrue(failing.failed.value)
    failing.dismiss()
    assertFalse(failing.failed.value)
    failing.save(record)
    assertTrue(failing.failed.value)
    db.close()
  }

  // Android #54: the ride was deleted while its ask waited for the answer; the ask is not kept for a ride that is gone.
  @Test
  fun an_ask_whose_ride_was_deleted_meanwhile_is_not_kept() = runBlocking {
    val rides = RoomRideStore(db.rides())
    rides.save(newRide("r1", "箱根", 500, 7, 24 * HOUR_MS).end(800, EndReason.MANUAL, 800))
    rides.delete("r1", 900)
    log.save(record)
    assertNull(db.asks().ask("a1"))
    assertFalse(log.failed.value)
    db.close()
  }

  // Slice 4 spec §4.2: an ask that never reached the Backend keeps when and where it was, and the heading's sample.
  @Test
  fun an_unreached_ask_is_kept_without_a_conversation_and_with_its_sample() = runBlocking {
    keepRide()
    val sample = HeadingSample(35.0, 138.999, "1970-01-01T00:00:00.500Z")
    log.save(unreachedAsk("u1", 1_000, Fix(35.001, 139.0, 1_000, 5.0), sample, "r1"))
    val stored = db.asks().ask("u1")!!
    assertEquals("offline", stored.status)
    assertNull(stored.conversationId)
    assertEquals(1_000L, stored.updatedAt)
    assertNull(stored.sentUpdatedAt)
    assertEquals(500L, stored.sampleAt)
    val record = stored.toRecord()
    assertEquals(AskOutcome.OFFLINE, record.outcome)
    assertEquals(sample, record.sample)
    assertFalse(record.sent)
    assertEquals(1, db.asks().countForRide("r1").first())
    db.close()
  }

  // Android #58: an ask dropped because its ride is not kept is logged, and the warning stays as it was.
  @Test
  fun an_ask_without_its_ride_is_logged_and_leaves_the_warning_as_it_was() = runBlocking {
    var full = true
    var rideKept = true
    val dao = object : AskDao {
      override suspend fun upsert(ask: AskEntity) = if (full) throw IOException("disk full") else Unit
      override suspend fun ask(askId: String): AskEntity? = null
      override suspend fun rideKept(rideId: String) = rideKept
      override fun countForRide(rideId: String) = kotlinx.coroutines.flow.flowOf(0)
      override fun forRide(rideId: String) = kotlinx.coroutines.flow.flowOf(emptyList<AskEntity>())
      override suspend fun setMemo(askId: String, memo: String?, at: Long) = error("not used")
      override suspend fun deleteRow(askId: String) = error("not used")
      override suspend fun markDeleted(deletion: DeletionEntity) = error("not used")
      override suspend fun unsentMemos(): List<AskMemoRow> = error("not used")
      override suspend fun markMemoSent(askId: String, updatedAt: Long) = error("not used")
      override suspend fun markMemoRefused(askId: String, updatedAt: Long) = error("not used")
      override suspend fun unsentUnreached(): List<AskEntity> = error("not used")
      override suspend fun markUnreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?) = error("not used")
      override suspend fun markUnreachedRefused(askId: String, updatedAt: Long) = error("not used")
    }
    val dropping = RoomAskLog(dao, kotlinx.coroutines.MainScope()) { message, _ -> errors += message }
    dropping.save(record)
    assertTrue(dropping.failed.value)
    full = false
    rideKept = false
    dropping.save(record.copy(askId = "a2"))
    assertTrue(dropping.failed.value)
    assertEquals("ask_dropped askId=a2 rideId=r1", errors.last())
    db.close()
  }
}
