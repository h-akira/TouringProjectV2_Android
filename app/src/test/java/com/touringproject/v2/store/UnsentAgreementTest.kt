package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.ask.unreachedAsk
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// What the next pass sends and what SC-14 counts are the same set (08a §6); the ride recording is the one difference.
@RunWith(AndroidJUnit4::class)
class UnsentAgreementTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val rides = RoomRideStore(db.rides())
  private val sync = RoomSyncStore(db.rides(), db.asks(), db.notes())
  private val records = RoomRecords(db.rides(), db.asks(), db.notes())
  private val log = RoomAskLog(db.asks(), kotlinx.coroutines.MainScope()) { _, _ -> }

  @After
  fun close() = db.close()

  private fun note(id: String, updatedAt: Long, sent: Long?, rejected: Long?) = NoteEntity(
    id, "r1", 1_500, 35.0, 139.0, null, null, null, "text", updatedAt,
    sentUpdatedAt = sent, rejectedUpdatedAt = rejected, address = null, headingDegrees = null,
  )

  @Test
  fun what_the_pass_sends_is_what_sc14_counts() = runBlocking {
    val ended = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS).end(5_000, EndReason.MANUAL, 5_000)
    rides.save(ended)
    rides.save(newRide("r2", "御殿場", 1_000, 7, 24 * HOUR_MS).end(5_000, EndReason.MANUAL, 5_000))
    rides.save(newRide("r3", "伊豆", 1_000, 7, 24 * HOUR_MS))
    sync.rideSent("r1", ended.updatedAt)
    sync.rideRefused("r2", 5_000)
    db.takeover().upsertNote(note("n1", 2_000, sent = null, rejected = null))
    db.takeover().upsertNote(note("n2", 2_000, sent = 2_000, rejected = null))
    db.takeover().upsertNote(note("n3", 2_000, sent = null, rejected = 2_000))
    log.save(unreachedAsk("u1", 2_000, null, null, "r1"))

    val summary = records.summary.first()
    assertEquals(sync.unsent().count { it.ride.status != RideStatus.RECORDING }, summary.unsentRides)
    assertEquals(sync.unsentNotes().size, summary.notes)
    assertEquals(sync.unsentUnreached().size, summary.unreached)
    assertEquals(sync.unsentAskMemos().size, summary.memos)
    assertEquals(1, summary.notes)
    assertEquals(1, summary.unreached)
  }
}
