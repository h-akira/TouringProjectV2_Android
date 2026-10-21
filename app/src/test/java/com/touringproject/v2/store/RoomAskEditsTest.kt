package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAskEditsTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private var now = 5_000L
  private var changes = 0
  private val errors = mutableListOf<String>()
  private val edits = RoomAskEdits(db.asks(), { now }, { changes += 1 }) { message, _ -> errors += message }

  private fun keep(askId: String = "a1", rideId: String = "r1") = runBlocking {
    db.asks().upsert(AskEntity(askId, 1_000, 2_000, "Q", "A", 35.0, 139.0, null, null, "voice", "c1", true, "answered", null, rideId))
  }

  private fun deletions(): List<DeletionEntity> = db.query("SELECT * FROM deletions", null).use { c ->
    buildList { while (c.moveToNext()) add(DeletionEntity(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
  }

  @After
  fun close() = db.close()

  @Test
  fun a_memo_is_kept_with_when_it_changed_and_is_something_to_send() = runBlocking {
    keep()
    assertTrue(edits.memo("a1", "また来たい"))
    val ask = db.asks().ask("a1")!!
    assertEquals("また来たい", ask.memo)
    assertEquals(5_000L, ask.memoUpdatedAt)
    assertEquals(1, changes)
  }

  // The Backend keeps only a newer memo, so a change made with the clock set back still comes after (slice 3 spec §6.2).
  @Test
  fun each_change_of_the_memo_is_later_than_the_one_before() = runBlocking {
    keep()
    edits.memo("a1", "一つ目")
    now = 3_000
    edits.memo("a1", null)
    val ask = db.asks().ask("a1")!!
    assertNull(ask.memo)
    assertEquals(5_001L, ask.memoUpdatedAt)
  }

  @Test
  fun the_same_memo_again_does_not_change_when_it_changed() = runBlocking {
    keep()
    edits.memo("a1", "同じ")
    now = 9_000
    assertTrue(edits.memo("a1", "同じ"))
    assertEquals(5_000L, db.asks().ask("a1")?.memoUpdatedAt)
  }

  @Test
  fun a_memo_for_an_ask_not_kept_or_too_long_is_refused() = runBlocking {
    keep()
    assertFalse(edits.memo("a9", "どこ"))
    assertFalse(edits.memo("a1", "あ".repeat(2001)))
    assertNull(db.asks().ask("a1")?.memo)
    assertEquals(0, changes)
  }

  @Test
  fun deleting_an_ask_marks_it_with_its_ride() = runBlocking {
    keep()
    keep("a2")
    assertTrue(edits.delete("a1"))
    assertNull(db.asks().ask("a1"))
    assertEquals("a2", db.asks().ask("a2")?.askId)
    assertEquals(listOf(DeletionEntity("ask", "a1", "r1", 5_000)), deletions())
    assertEquals(1, changes)
    assertFalse(edits.delete("a1"))
  }

  // Slice 3 spec §8: a write that fails is reported, so SC-08 keeps what was typed.
  @Test
  fun a_write_that_fails_is_logged_and_reported() = runBlocking {
    keep()
    db.close()
    assertFalse(edits.memo("a1", "書けない"))
    assertFalse(edits.delete("a1"))
    assertEquals(2, errors.size)
    assertEquals(0, changes)
  }
}
