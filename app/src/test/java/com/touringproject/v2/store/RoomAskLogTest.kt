package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.ask.ASK_INPUT_VOICE
import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  @Test
  fun a_kept_ask_is_stored_with_its_ride_and_outcome() = runBlocking {
    log.save(record)
    val stored = db.asks().ask("a1")!!
    assertEquals("answered", stored.status)
    assertEquals("r1", stored.rideId)
    assertTrue(stored.newConversation)
    assertFalse(log.failed.value)
    db.close()
  }

  @Test
  fun a_failed_write_is_logged_and_shown_until_the_next_one_works() = runBlocking {
    var full = true
    val dao = object : AskDao {
      override suspend fun upsert(ask: AskEntity) = if (full) throw IOException("disk full") else Unit
      override suspend fun ask(askId: String): AskEntity? = null
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
}
