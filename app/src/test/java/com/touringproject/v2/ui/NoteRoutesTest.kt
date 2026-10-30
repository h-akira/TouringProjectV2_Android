package com.touringproject.v2.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.lifecycle.Lifecycle
import com.touringproject.v2.handsfree.PressRouter
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
import com.touringproject.v2.audio.Recorded
import com.touringproject.v2.audio.Recorder
import com.touringproject.v2.audio.RouteDriver
import com.touringproject.v2.audio.RouteLeases
import com.touringproject.v2.audio.Source
import com.touringproject.v2.voice.VOICE_IDLE
import com.touringproject.v2.voice.VoiceDeps
import com.touringproject.v2.voice.VoicePhase
import com.touringproject.v2.voice.VoiceState
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
  private val draft = NoteDraft("n1", "r1", 4_000, 35.3, 138.9, null)

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

  private val idleRoute = RouteLeases(object : RouteDriver {
    override suspend fun start(source: Source) = true
    override suspend fun stop() = Unit
    override fun inputDevice() = null
  })
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

  // Review R3-01: the voice input is attached only while SC-19 is in front. An Activity that is stopped (home, app
  // switch, screen off) gives the intercom button back to the ask; coming back to the front attaches it again.
  @Test
  fun sc19_gives_the_intercom_button_back_while_the_app_is_in_the_background() {
    keepRide()
    var shown by mutableStateOf(true)
    rule.setContent {
      if (shown) NewNoteRoute(records, notes, draft, onClose = {}, voice = NoteVoice(input, MutableStateFlow(VOICE_IDLE), MutableStateFlow(true)))
    }
    waitFor("voice")
    assertTrue(input.attached)
    rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    rule.waitUntil(5_000) { !input.attached }
    rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    rule.waitUntil(5_000) { input.attached }
    // Leave the screen before the database closes: the records are read again once it is back in front.
    shown = false
    rule.waitForIdle()
    assertFalse(input.attached)
  }

  // Review R3-01: a press while SC-19 is behind the navigation app starts an ask, not a voice input.
  @Test
  fun an_intercom_press_while_sc19_is_in_the_background_starts_an_ask() {
    keepRide()
    var asks = 0
    var voicePresses = 0
    val router = PressRouter(
      trialActive = { false }, stopTrial = {}, stopCheckPlayback = {},
      askPress = { asks++ }, askStopRecording = {}, askTap = {},
      voiceAttached = { input.attached }, voicePress = { voicePresses++ },
    )
    rule.setContent {
      NewNoteRoute(records, notes, draft, onClose = {}, voice = NoteVoice(input, MutableStateFlow(VOICE_IDLE), MutableStateFlow(true)))
    }
    waitFor("voice")
    router.press()
    assertEquals(1, voicePresses)
    rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    rule.waitUntil(5_000) { !input.attached }
    router.press()
    assertEquals(1, asks)
    assertEquals(1, voicePresses)
  }

  // Final review of slice 6 plan 1: pausing drops a recording, so the screen must not time out while the voice input
  // is recording or making the text (the rider's hands are on the bike, not on the screen).
  @Test
  fun the_screen_stays_on_while_the_voice_input_is_working() {
    keepRide()
    val state = MutableStateFlow(VOICE_IDLE)
    lateinit var view: android.view.View
    rule.setContent {
      view = androidx.compose.ui.platform.LocalView.current
      NewNoteRoute(records, notes, draft, onClose = {}, voice = NoteVoice(input, state, MutableStateFlow(true)))
    }
    waitFor("voice")
    assertFalse(view.keepScreenOn)
    state.value = VoiceState(phase = VoicePhase.RECORDING)
    rule.waitUntil(5_000) { view.keepScreenOn }
    state.value = VoiceState(phase = VoicePhase.TRANSCRIBING)
    rule.waitForIdle()
    assertTrue(view.keepScreenOn)
    state.value = VOICE_IDLE
    rule.waitUntil(5_000) { !view.keepScreenOn }
  }

  // Android #57: a memo or ride that is not found says so, and the screen can still be closed.
  @Test
  fun a_memo_not_found_says_so_and_can_be_closed() {
    keepRide()
    rule.setContent { NoteRoute(records, notes, "r1", "n9", onClose = { closed++ }) }
    waitFor("notFound")
    rule.onNodeWithTag("notFound").assertTextEquals("この地点メモは見つかりません。")
    rule.onNodeWithTag("notFoundClose").performClick()
    assertEquals(1, closed)
  }
}
