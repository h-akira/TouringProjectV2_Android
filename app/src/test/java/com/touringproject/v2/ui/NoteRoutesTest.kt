package com.touringproject.v2.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.records.NoteDraft
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.store.RideDatabase
import com.touringproject.v2.store.RoomNotes
import com.touringproject.v2.store.RoomRecords
import com.touringproject.v2.store.RoomRideStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// SC-19 on the device's records: a memo from Home is kept for its ride, and one from SC-06 is changed or deleted.
@RunWith(AndroidJUnit4::class)
class NoteRoutesTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val records = RoomRecords(db.rides(), db.asks(), db.notes())
  private val notes = RoomNotes(db.notes(), db.rides(), { 5_000 }, { "n1" }, {}) { _, _ -> }
  private var closed = 0
  private val draft = NoteDraft("n1", "r1", 4_000, 35.3, 138.9, null, null, null)

  @After
  fun close() = db.close()

  private fun keepRide() = runBlocking { RoomRideStore(db.rides()).save(newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS)) }

  private fun waitFor(tag: String) = rule.waitUntil(5_000) {
    rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
  }

  @Test
  fun a_memo_from_home_is_kept_for_its_ride() {
    keepRide()
    rule.setContent { NewNoteRoute(records, notes, draft, onClose = { closed++ }) }
    waitFor("noteRide")
    rule.onNodeWithTag("noteRide").assertTextEquals("走行「箱根」に入ります")
    rule.onNodeWithTag("memoInput").performTextReplacement("展望スペース")
    rule.onNodeWithTag("memoSave").performClick()
    rule.waitUntil(5_000) { closed == 1 }
    assertEquals("展望スペース", runBlocking { db.notes().note("n1")?.text })
  }

  // The memo turns "not kept" on screen as soon as it is deleted; the screen still closes, once.
  @Test
  fun a_kept_memo_is_changed_or_deleted_and_the_screen_closes_once() {
    keepRide()
    runBlocking { notes.create(draft, "前のメモ") }
    rule.setContent { NoteRoute(records, notes, "r1", "n1", onClose = { closed++ }) }
    waitFor("noteRide")
    rule.onNodeWithTag("noteRide").assertTextEquals("走行「箱根」で書いたメモ")
    rule.onNodeWithTag("deleteNote").performClick()
    rule.onNodeWithTag("deleteGo").performClick()
    rule.waitUntil(5_000) { closed == 1 }
    rule.waitForIdle()
    assertEquals(1, closed)
    assertNull(runBlocking { db.notes().note("n1") })
  }
}
