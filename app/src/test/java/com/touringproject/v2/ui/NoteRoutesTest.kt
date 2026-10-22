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
import com.touringproject.v2.ask.Recorded
import com.touringproject.v2.ask.Recorder
import com.touringproject.v2.ask.RouteControl
import com.touringproject.v2.ask.Source
import com.touringproject.v2.voice.VOICE_IDLE
import com.touringproject.v2.voice.VoiceDeps
import com.touringproject.v2.voice.VoiceInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

  private val idleRoute = object : RouteControl {
    override suspend fun start(source: Source) = true
    override suspend fun stop() = Unit
    override fun inputDevice() = null
  }
  private val idleRecorder = object : Recorder {
    override suspend fun start() = Unit
    override suspend fun stop() = Recorded(ByteArray(0), "voice.pcm")
    override suspend fun cancel() = Unit
  }
  private val input = VoiceInput(
    VoiceDeps({ 0 }, {}, { true }, { true }, { null }, idleRoute, idleRecorder, {}, {}, {}) { _, _ -> },
    MainScope(),
  )

  // Slice 3 spec §7.3: the intercom button belongs to SC-19's voice input only while SC-19 is shown.
  @Test
  fun sc19_takes_the_intercom_button_while_it_is_shown() {
    keepRide()
    var shown by mutableStateOf(true)
    rule.setContent {
      if (shown) NewNoteRoute(records, notes, draft, onClose = {}, voice = NoteVoice(input, MutableStateFlow(VOICE_IDLE), MutableStateFlow(true)))
    }
    waitFor("voice")
    assertTrue(input.attached)
    shown = false
    rule.waitForIdle()
    assertFalse(input.attached)
  }
}
