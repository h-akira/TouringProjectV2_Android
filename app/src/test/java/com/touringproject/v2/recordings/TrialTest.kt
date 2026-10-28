package com.touringproject.v2.recordings

import com.touringproject.v2.ask.Recorded
import com.touringproject.v2.ask.Recorder
import com.touringproject.v2.ask.RouteControl
import com.touringproject.v2.ask.Source
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.settings.DEFAULT_RECORD_LIMIT_MS
import com.touringproject.v2.settings.DEFAULT_START_CUE_MS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val INTERCOM = InputDevice(InputDevice.Type.BLUETOOTH, "M1-S PRO")

/** A fake world around the trial controller that records what it was asked to do, in order. */
private class TrialHarness(
  val scope: TestScope,
  val micAllowed: Boolean = true,
  val routeOk: Boolean = true,
  var routeGate: CompletableDeferred<Unit>? = null,
  val recorderError: Exception? = null,
  val pcm: ByteArray = byteArrayOf(1, 2),
  var playGate: CompletableDeferred<Unit>? = null,
  val playError: Exception? = null,
  /** SC-11's values (slice 5 spec §6). */
  val limitMs: Long = DEFAULT_RECORD_LIMIT_MS,
  val cueMs: Int = DEFAULT_START_CUE_MS,
) {
  val log = mutableListOf<String>()
  val cues = mutableListOf<Int>()
  val states = mutableListOf<TrialState>()
  val trial = TrialController(
    TrialDeps(
      now = { scope.testScheduler.currentTime },
      sleep = { delay(it) },
      micAllowed = { micAllowed },
      route = object : RouteControl {
        override suspend fun start(source: Source): Boolean {
          log += "route:${source.name.lowercase()}"
          routeGate?.await()
          return routeOk
        }
        override suspend fun stop() { log += "route:stop" }
        override fun inputDevice() = INTERCOM
      },
      recorder = object : Recorder {
        override suspend fun start() { log += "rec:start"; recorderError?.let { throw it } }
        override suspend fun stop(): Recorded { log += "rec:stop"; return Recorded(pcm, "trial.pcm") }
        override suspend fun cancel() { log += "rec:cancel" }
      },
      cueStart = { log += "cue:start"; cues += it },
      player = object : PcmPlayback {
        override suspend fun play(pcm: ByteArray) {
          log += "play:${pcm.size}"
          playError?.let { throw it }
          playGate?.await()
        }
        override fun stop() {
          log += "play:stop"
          playGate?.complete(Unit)
        }
      },
      onState = { states += it },
      recordLimitMs = { limitMs },
      startCueMs = { cueMs },
    ),
  )

  fun launchStart() = scope.launch { trial.start() }

  fun until(condition: () -> Boolean) {
    var steps = 0
    while (!condition()) {
      scope.testScheduler.advanceTimeBy(1)
      scope.testScheduler.runCurrent()
      check(++steps < 1_000_000) { "condition never became true" }
    }
  }
}

class TrialTest {
  @Test
  fun records_until_stopped_then_plays_it_back_and_shows_the_microphone() = runTest {
    val h = TrialHarness(this)
    val job = h.launchStart()
    h.until { "cue:start" in h.log }
    assertEquals(TrialPhase.RECORDING, h.trial.state.phase)
    h.trial.stop()
    job.join()
    assertEquals(listOf("route:screen", "rec:start", "cue:start", "rec:stop", "route:stop", "play:2"), h.log)
    assertEquals(TrialState(device = INTERCOM), h.trial.state)
    assertTrue(h.states.any { it.phase == TrialPhase.PLAYING })
  }

  @Test
  fun recording_stops_by_itself_at_the_limit() = runTest {
    val h = TrialHarness(this)
    h.trial.start()
    assertTrue("play:2" in h.log)
    assertTrue(testScheduler.currentTime >= DEFAULT_RECORD_LIMIT_MS)
    assertEquals(listOf(1_500), h.cues)
  }

  // US-E-03.001: the trial records with SC-11's limit and start cue, as a question does.
  @Test
  fun the_trial_takes_sc_11s_limit_and_cue() = runTest {
    val h = TrialHarness(this, limitMs = 35_000, cueMs = 0)
    h.trial.start()
    assertTrue(testScheduler.currentTime in 35_000L until 36_000L)
    assertEquals(listOf(0), h.cues)
  }

  @Test
  fun the_button_during_playback_stops_the_playback() = runTest {
    val h = TrialHarness(this, playGate = CompletableDeferred())
    val job = h.launchStart()
    h.until { "cue:start" in h.log }
    h.trial.stop()
    h.until { h.trial.state.phase == TrialPhase.PLAYING }
    h.trial.stop()
    job.join()
    assertTrue("play:stop" in h.log)
    assertEquals(TrialPhase.IDLE, h.trial.state.phase)
  }

  @Test
  fun leaving_while_the_route_is_set_up_never_opens_the_microphone() = runTest {
    // React Native version's issue #10: the old trial must not record or beep once SC-12 is left.
    val gate = CompletableDeferred<Unit>()
    val h = TrialHarness(this, routeGate = gate)
    val job = h.launchStart()
    h.until { "route:screen" in h.log }
    h.trial.cancel()
    assertEquals(TRIAL_IDLE, h.trial.state)
    h.trial.start() // still unwinding: ignored rather than sharing the recorder
    gate.complete(Unit)
    job.join()
    assertEquals(listOf("route:screen", "play:stop", "route:stop"), h.log)
    assertEquals(TRIAL_IDLE, h.trial.state)
  }

  @Test
  fun a_trial_left_during_route_setup_stays_busy_until_it_has_unwound() = runTest {
    // An ask must not take the route while the old trial can still set up or release SCO under it.
    val gate = CompletableDeferred<Unit>()
    val h = TrialHarness(this, routeGate = gate)
    val job = h.launchStart()
    h.until { "route:screen" in h.log }
    h.trial.cancel()
    assertFalse(h.trial.active)
    assertTrue(h.trial.busy)
    gate.complete(Unit)
    job.join()
    assertFalse(h.trial.busy)
  }

  @Test
  fun leaving_while_recording_drops_the_recording() = runTest {
    val h = TrialHarness(this)
    val job = h.launchStart()
    h.until { "cue:start" in h.log }
    h.trial.cancel()
    job.join()
    assertEquals(listOf("route:screen", "rec:start", "cue:start", "play:stop", "rec:cancel", "route:stop"), h.log)
    assertFalse(h.log.any { it.startsWith("play:2") })
  }

  @Test
  fun without_the_microphone_permission_it_says_so() = runTest {
    val h = TrialHarness(this, micAllowed = false)
    h.trial.start()
    assertEquals(TrialState(error = TRIAL_MIC_MESSAGE), h.trial.state)
    assertTrue(h.log.isEmpty())
  }

  @Test
  fun a_recorder_that_cannot_start_releases_the_route() = runTest {
    val h = TrialHarness(this, recorderError = IllegalStateException("busy"))
    h.trial.start()
    assertEquals(listOf("route:screen", "rec:start", "route:stop"), h.log)
    assertEquals(TRIAL_RECORD_MESSAGE, h.trial.state.error)
  }

  @Test
  fun an_empty_recording_is_not_played() = runTest {
    val h = TrialHarness(this, pcm = ByteArray(0))
    h.trial.start()
    assertFalse(h.log.any { it.startsWith("play:") })
    assertEquals(TRIAL_RECORD_MESSAGE, h.trial.state.error)
  }

  @Test
  fun a_playback_that_fails_is_reported() = runTest {
    val h = TrialHarness(this, playError = IllegalStateException("no track"))
    h.trial.start()
    assertEquals(TRIAL_PLAY_MESSAGE, h.trial.state.error)
    assertEquals(TrialPhase.IDLE, h.trial.state.phase)
  }

  @Test
  fun the_phone_microphone_is_shown_when_the_intercom_is_not_reached() = runTest {
    val h = TrialHarness(this, routeOk = false)
    h.trial.start()
    assertTrue(h.trial.state.phoneMic)
    assertNull(h.trial.state.error)
  }
}
