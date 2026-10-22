package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.records.NoteDraft
import com.touringproject.v2.records.NoteRecord
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RoomNotesTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val rides = RoomRideStore(db.rides())
  private var now = 5_000L
  private var changes = 0
  private val errors = mutableListOf<String>()
  private val notes = RoomNotes(db.notes(), db.rides(), { now }, { "n1" }, { changes += 1 }) { message, _ -> errors += message }
  private val records = RoomRecords(db.rides(), db.asks(), db.notes())
  private val ride = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS)

  @After
  fun close() = db.close()

  private fun deletions(): List<DeletionEntity> = db.query("SELECT * FROM deletions", null).use { c ->
    buildList { while (c.moveToNext()) add(DeletionEntity(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
  }

  private val draft = NoteDraft("n1", "r1", 4_000, 35.02, 139.0, 35.0, 139.0, 3_000)

  // Slice 3 spec §4.3: the memo opened now is at the ride's newest point; nothing is written until it is saved.
  @Test
  fun a_draft_is_at_the_rides_newest_point_and_writes_nothing() = runBlocking {
    rides.save(ride)
    rides.addPoint("r1", RoutePoint(0, 2_000, 35.0, 139.0, 5.0), 0.0, 2_000)
    rides.addPoint("r1", RoutePoint(0, 3_000, 35.01, 139.0, 5.0), 0.0, 3_000)
    assertEquals(NoteDraft("n1", "r1", 5_000, 35.01, 139.0, 35.0, 139.0, 2_000), notes.draft("r1"))
    assertEquals(emptyList<NoteRecord>(), records.notes("r1").first())
  }

  @Test
  fun a_saved_memo_keeps_its_place_and_time_and_is_something_to_send() = runBlocking {
    rides.save(ride)
    assertTrue(notes.create(draft, "展望スペース"))
    val kept = db.notes().note("n1")!!
    assertEquals(listOf(4_000L, 5_000L), listOf(kept.at, kept.updatedAt))
    assertEquals(listOf(35.02, 139.0, 35.0, 139.0), listOf(kept.latitude, kept.longitude, kept.sampleLatitude, kept.sampleLongitude))
    assertEquals(3_000L, kept.sampleAt)
    assertNull(kept.sentUpdatedAt)
    assertEquals(1, changes)
    assertEquals(listOf(NoteRecord("n1", "r1", 4_000, 35.02, 139.0, "展望スペース", null, sent = false)), records.notes("r1").first())
  }

  // SC-19: an empty memo is not saved, nor one for a ride not kept (deleted meanwhile).
  @Test
  fun an_empty_or_too_long_memo_or_one_for_a_ride_not_kept_is_refused() = runBlocking {
    assertFalse(notes.create(draft, "どこ"))
    rides.save(ride)
    assertFalse(notes.create(draft, " "))
    assertFalse(notes.create(draft, "あ".repeat(2001)))
    assertNull(db.notes().note("n1"))
    assertEquals(0, changes)
  }

  // The ride ended by its longest time while SC-19 was open: the memo still goes into the ride it was opened in.
  @Test
  fun a_memo_opened_before_its_ride_ended_is_still_kept_in_it() = runBlocking {
    rides.save(ride.end(4_500, EndReason.AUTO, 4_500))
    assertTrue(notes.create(draft, "終わったあとに保存"))
    assertEquals("r1", db.notes().note("n1")?.rideId)
  }

  // The Backend keeps only a newer version, so a change made with the clock set back still comes after.
  @Test
  fun each_change_of_the_text_is_later_than_the_one_before_and_the_same_text_changes_nothing() = runBlocking {
    rides.save(ride)
    notes.create(draft, "一つ目")
    now = 3_000
    assertTrue(notes.text("n1", "二つ目"))
    assertEquals("二つ目" to 5_001L, db.notes().note("n1")!!.let { it.text to it.updatedAt })
    assertEquals(2, changes)
    now = 9_000
    assertTrue(notes.text("n1", "二つ目"))
    assertEquals(5_001L, db.notes().note("n1")!!.updatedAt)
    assertFalse(notes.text("n9", "どこ"))
    assertFalse(notes.text("n1", " "))
  }

  @Test
  fun deleting_a_memo_marks_it_with_its_ride() = runBlocking {
    rides.save(ride)
    notes.create(draft, "消す")
    assertTrue(notes.delete("n1"))
    assertNull(db.notes().note("n1"))
    assertEquals(listOf(DeletionEntity("note", "n1", "r1", 5_000)), deletions())
    assertFalse(notes.delete("n1"))
  }

  // A deleted ride takes its point memos with it (slice 3 spec §4.2).
  @Test
  fun deleting_the_ride_removes_its_memos() = runBlocking {
    rides.save(ride.end(4_500, EndReason.MANUAL, 4_500))
    notes.create(draft, "走行ごと消える")
    rides.delete("r1", 6_000)
    assertNull(db.notes().note("n1"))
    assertEquals(listOf("ride"), deletions().map { it.kind })
  }

  // Slice 3 spec §8: a write that fails is reported, so SC-19 keeps what was typed.
  @Test
  fun a_write_that_fails_is_logged_and_reported() = runBlocking {
    rides.save(ride)
    db.close()
    assertFalse(notes.create(draft, "書けない"))
    assertFalse(notes.text("n1", "書けない"))
    assertFalse(notes.delete("n1"))
    assertEquals(3, errors.size)
    assertEquals(0, changes)
  }

  // A draft whose points cannot be read is opened without a place rather than not at all.
  @Test
  fun a_draft_whose_points_cannot_be_read_has_no_place() = runBlocking {
    val failing = object : RideDao by db.rides() {
      override suspend fun lastPoints(rideId: String, count: Int): List<RoutePointEntity> = throw IOException("disk")
    }
    val notes = RoomNotes(db.notes(), failing, { now }, { "n1" }, { changes += 1 }) { message, _ -> errors += message }
    assertEquals(NoteDraft("n1", "r1", 5_000, null, null, null, null, null), notes.draft("r1"))
    assertEquals(1, errors.size)
  }
}
