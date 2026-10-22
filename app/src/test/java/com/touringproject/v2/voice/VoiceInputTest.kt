package com.touringproject.v2.voice

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.TranscriptionAccepted
import com.touringproject.v2.api.TranscriptionApi
import com.touringproject.v2.api.TranscriptionBody
import com.touringproject.v2.api.TranscriptionResult
import com.touringproject.v2.ask.Recorded
import com.touringproject.v2.ask.Recorder
import com.touringproject.v2.ask.RouteControl
import com.touringproject.v2.ask.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1 s of 16 kHz mono 16-bit PCM. */
private val ONE_SECOND = ByteArray(32_000)

/** A fake world around the voice input that records what it was asked to do, in order. */
private class VoiceHarness(
  val scope: TestScope,
  var online: Boolean = true,
  val micAllowed: Boolean = true,
  val hasKey: Boolean = true,
  val recorderError: Exception? = null,
  var pcm: ByteArray = ONE_SECOND,
  val postError: Exception? = null,
  /** What each GET answers, in turn; the last one repeats. */
  val results: MutableList<TranscriptionResult> = mutableListOf(TranscriptionResult("t1", "done", "展望スペース")),
  var getGate: CompletableDeferred<Unit>? = null,
) {
  val log = mutableListOf<String>()
  val states = mutableListOf<VoiceState>()
  val api = object : TranscriptionApi {
    override suspend fun postTranscription(body: TranscriptionBody): TranscriptionAccepted {
      log += "post:${body.audio.length}"
      postError?.let { throw it }
      return TranscriptionAccepted("t1")
    }
    override suspend fun getTranscription(transcriptionId: String): TranscriptionResult {
      log += "get"
      getGate?.await()
      return if (results.size > 1) results.removeAt(0) else results[0]
    }
  }
  val voice = VoiceInput(
    VoiceDeps(
      now = { scope.testScheduler.currentTime },
      sleep = { delay(it) },
      micAllowed = { micAllowed },
      online = { online },
      api = { if (hasKey) api else null },
      route = object : RouteControl {
        override suspend fun start(source: Source): Boolean {
          log += "route:${source.name.lowercase()}"
          return true
        }
        override suspend fun stop() { log += "route:stop" }
        override fun inputDevice() = null
      },
      recorder = object : Recorder {
        override suspend fun start() { log += "rec:start"; recorderError?.let { throw it } }
        override suspend fun stop(): Recorded { log += "rec:stop"; return Recorded(pcm, "voice.pcm") }
        override suspend fun cancel() { log += "rec:cancel" }
      },
      cueStart = { log += "cue:start" },
      cueFailure = { log += "cue:failure" },
      onState = { states += it },
      log = { _, _ -> },
    ),
    scope,
  )
}

class VoiceInputTest {
  // Slice 3 spec §7.3: press to record, press again to stop and send; what was heard comes back to be added.
  @Test
  fun a_second_press_stops_the_recording_and_what_was_heard_comes_back() = runTest {
    val h = VoiceHarness(this)
    h.voice.press(Source.SCREEN)
    runCurrent()
    assertEquals(VoicePhase.RECORDING, h.voice.state.phase)
    advanceTimeBy(3_000)
    h.voice.press(Source.SCREEN)
    advanceUntilIdle()
    assertEquals(listOf("route:screen", "rec:start", "cue:start", "rec:stop", "route:stop", "post:${ONE_SECOND.size / 3 * 4 + 4}", "get"), h.log)
    assertEquals(Heard(1, "展望スペース"), h.voice.state.heard)
    assertEquals(VoicePhase.IDLE, h.voice.state.phase)
    assertTrue(h.states.any { it.phase == VoicePhase.TRANSCRIBING })
  }

  @Test
  fun the_recording_stops_by_itself_after_60_seconds() = runTest {
    val h = VoiceHarness(this)
    h.voice.press(Source.HANDSFREE)
    advanceTimeBy(VOICE_LIMIT_MS - 1_000)
    assertEquals(VoicePhase.RECORDING, h.voice.state.phase)
    advanceUntilIdle()
    assertEquals(Heard(1, "展望スペース"), h.voice.state.heard)
    assertTrue("rec:stop" in h.log)
  }

  // The result is fetched every second at first, as an ask's is, until it is no longer processing.
  @Test
  fun the_text_is_fetched_until_it_is_ready() = runTest {
    val h = VoiceHarness(this, results = mutableListOf(TranscriptionResult("t1", "processing"), TranscriptionResult("t1", "processing"), TranscriptionResult("t1", "done", " 給油 ")))
    h.voice.press(Source.SCREEN)
    runCurrent()
    h.voice.press(Source.SCREEN)
    advanceUntilIdle()
    assertEquals(3, h.log.count { it == "get" })
    assertEquals(Heard(1, "給油"), h.voice.state.heard)
  }

  // Taking what was heard clears it, so the screen adds it once; the next one gets a new number.
  @Test
  fun what_was_heard_is_taken_once_and_the_next_one_is_numbered_anew() = runTest {
    val h = VoiceHarness(this)
    repeat(2) {
      h.voice.press(Source.SCREEN)
      runCurrent()
      h.voice.press(Source.SCREEN)
      advanceUntilIdle()
      val heard = h.voice.state.heard!!
      assertEquals(it + 1, heard.seq)
      h.voice.taken(heard.seq)
      assertNull(h.voice.state.heard)
    }
  }

  // Slice 3 spec §7.3: without a network the voice cannot be sent; the intercom button only gets the failure cue.
  @Test
  fun without_a_network_a_press_records_nothing_and_gives_the_failure_cue() = runTest {
    val h = VoiceHarness(this, online = false)
    h.voice.press(Source.HANDSFREE)
    advanceUntilIdle()
    assertEquals(listOf("cue:failure"), h.log)
    assertEquals(VOICE_IDLE, h.voice.state)
  }

  @Test
  fun a_press_while_the_text_is_being_made_does_nothing() = runTest {
    val h = VoiceHarness(this, getGate = CompletableDeferred())
    h.voice.press(Source.SCREEN)
    runCurrent()
    h.voice.press(Source.SCREEN)
    advanceTimeBy(1_500)
    assertEquals(VoicePhase.TRANSCRIBING, h.voice.state.phase)
    h.voice.press(Source.SCREEN)
    runCurrent()
    assertEquals(1, h.log.count { it == "rec:start" })
    h.getGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals("展望スペース", h.voice.state.heard?.text)
  }

  @Test
  fun the_reason_of_a_failure_is_told_with_the_failure_cue() = runTest {
    val cases = listOf(
      VoiceHarness(this, micAllowed = false) to VoiceFailure.MIC,
      VoiceHarness(this, recorderError = IllegalStateException("busy")) to VoiceFailure.MIC,
      VoiceHarness(this, pcm = ByteArray(0)) to VoiceFailure.MIC,
      VoiceHarness(this, pcm = ByteArray(32 * 200)) to VoiceFailure.NO_SPEECH,
      VoiceHarness(this, hasKey = false) to VoiceFailure.KEY,
      VoiceHarness(this, postError = ApiError(ApiErrorKind.INVALID_KEY)) to VoiceFailure.KEY,
      VoiceHarness(this, postError = ApiError(ApiErrorKind.NETWORK)) to VoiceFailure.NETWORK,
      VoiceHarness(this, results = mutableListOf(TranscriptionResult("t1", "failed", failure = "no_speech"))) to VoiceFailure.NO_SPEECH,
      VoiceHarness(this, results = mutableListOf(TranscriptionResult("t1", "done", " "))) to VoiceFailure.NO_SPEECH,
      VoiceHarness(this, results = mutableListOf(TranscriptionResult("t1", "failed", failure = "transcribe_failed"))) to VoiceFailure.TRANSCRIBE_FAILED,
      VoiceHarness(this, results = mutableListOf(TranscriptionResult("t1", "processing"))) to VoiceFailure.NETWORK,
    )
    cases.forEach { (h, failure) ->
      h.voice.press(Source.SCREEN)
      runCurrent()
      h.voice.press(Source.SCREEN)
      advanceUntilIdle()
      assertEquals(VoiceState(failure = failure), h.voice.state)
      assertEquals("cue:failure", h.log.last())
    }
  }

  // A recording too short for the Backend (under 0.25 s) is not sent at all.
  @Test
  fun a_recording_too_short_is_not_sent() = runTest {
    val h = VoiceHarness(this, pcm = ByteArray(32 * 200))
    h.voice.press(Source.SCREEN)
    runCurrent()
    h.voice.press(Source.SCREEN)
    advanceUntilIdle()
    assertTrue(h.log.none { it.startsWith("post") })
  }

  @Test
  fun a_new_recording_clears_the_reason_of_the_last_failure() = runTest {
    val h = VoiceHarness(this, results = mutableListOf(TranscriptionResult("t1", "failed", failure = "no_speech"), TranscriptionResult("t1", "done", "二度目")))
    h.voice.press(Source.SCREEN)
    runCurrent()
    h.voice.press(Source.SCREEN)
    advanceUntilIdle()
    assertEquals(VoiceFailure.NO_SPEECH, h.voice.state.failure)
    h.voice.press(Source.SCREEN)
    runCurrent()
    assertEquals(VoiceState(VoicePhase.RECORDING), h.voice.state)
  }

  // Leaving SC-19 drops what is going on; a result that comes later is not added anywhere.
  @Test
  fun leaving_drops_the_recording_or_the_text_still_being_made() = runTest {
    val recording = VoiceHarness(this)
    recording.voice.attach()
    recording.voice.press(Source.SCREEN)
    runCurrent()
    recording.voice.detach()
    advanceUntilIdle()
    assertEquals(listOf("route:screen", "rec:start", "cue:start", "rec:cancel", "route:stop"), recording.log)
    assertEquals(VOICE_IDLE, recording.voice.state)
    assertEquals(false, recording.voice.attached)

    val making = VoiceHarness(this, getGate = CompletableDeferred())
    making.voice.press(Source.SCREEN)
    runCurrent()
    making.voice.press(Source.SCREEN)
    advanceTimeBy(1_500)
    making.voice.detach()
    making.getGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(VOICE_IDLE, making.voice.state)
  }

  // The headset ending its voice recognition is the second press of the intercom button.
  @Test
  fun the_headset_ending_the_session_stops_the_recording() = runTest {
    val h = VoiceHarness(this)
    h.voice.press(Source.HANDSFREE)
    runCurrent()
    h.voice.stop()
    advanceUntilIdle()
    assertEquals(Heard(1, "展望スペース"), h.voice.state.heard)
  }
}
