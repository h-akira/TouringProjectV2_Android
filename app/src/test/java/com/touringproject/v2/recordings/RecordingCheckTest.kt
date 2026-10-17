package com.touringproject.v2.recordings

import com.touringproject.v2.ask.RecordingMeta
import com.touringproject.v2.ask.Recorded
import com.touringproject.v2.ask.Recorder
import com.touringproject.v2.ask.RouteControl
import com.touringproject.v2.ask.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun meta(time: Long) = RecordingMeta("rec-$time.pcm", time, 1_000, false, null, null)

class RecordingCheckTest {
  @get:Rule val folder = TemporaryFolder()

  private class World(scope: TestScope, dir: File, var askBusy: Boolean = false, val playError: Exception? = null) {
    val log = mutableListOf<String>()
    var playGate: CompletableDeferred<Unit>? = null
    val store = RecordingStore(dir, io = StandardTestDispatcher(scope.testScheduler))
    val player = object : PcmPlayback {
      override suspend fun play(pcm: ByteArray) {
        log += "play:${pcm.toList()}"
        playError?.let { throw it }
        playGate?.await()
      }
      override fun stop() {
        log += "play:stop"
        playGate?.complete(Unit)
      }
    }
    val trial = TrialController(
      TrialDeps(
        now = { scope.testScheduler.currentTime },
        sleep = { delay(it) },
        micAllowed = { true },
        route = object : RouteControl {
          override suspend fun start(source: Source) = true
          override suspend fun stop() = Unit
          override fun inputDevice() = null
        },
        recorder = object : Recorder {
          override suspend fun start() = Unit
          override suspend fun stop() = Recorded(byteArrayOf(9), "trial.pcm")
          override suspend fun cancel() = Unit
        },
        cueStart = { log += "cue:start" },
        player = object : PcmPlayback {
          override suspend fun play(pcm: ByteArray) { log += "trial:play" }
          override fun stop() { log += "trial:stop" }
        },
        onState = {},
      ),
    )
    // Like the app's scope: runs on the test clock, and advanceUntilIdle waits for it (backgroundScope would not).
    private val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
    val check = RecordingCheck(store, trial, player, { askBusy }, appScope) { message, _ -> log += message }
  }

  @Test
  fun the_list_follows_the_store_newest_first() = runTest {
    val w = World(this, folder.root)
    w.store.save(meta(1), byteArrayOf(1))
    w.store.save(meta(2), byteArrayOf(2))
    testScheduler.advanceUntilIdle()
    assertEquals(listOf(meta(2), meta(1)), w.check.items.value)
    w.store.setTranscript("rec-1.pcm", "富士山")
    testScheduler.advanceUntilIdle()
    assertEquals("富士山", w.check.items.value[1].transcript)
  }

  @Test
  fun a_kept_recording_is_played_from_its_file() = runTest {
    val w = World(this, folder.root)
    w.store.save(meta(1), byteArrayOf(4, 5))
    w.playGate = CompletableDeferred()
    w.check.play("rec-1.pcm")
    testScheduler.advanceUntilIdle()
    assertEquals("rec-1.pcm", w.check.playing.value)
    w.check.stopPlaying()
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("play:[4, 5]", "play:stop"), w.log)
    assertNull(w.check.playing.value)
  }

  @Test
  fun a_stop_before_the_file_is_read_keeps_the_recording_silent() = runTest {
    // An intercom press right after ▶ (4b #10): the recording must not start playing into the question.
    val w = World(this, folder.root)
    w.store.save(meta(1), byteArrayOf(1))
    testScheduler.advanceUntilIdle()
    w.check.play("rec-1.pcm")
    w.check.stopPlaying()
    testScheduler.advanceUntilIdle()
    assertTrue(w.log.none { it.startsWith("play:[") })
    assertNull(w.check.playing.value)
  }

  @Test
  fun a_recording_that_cannot_be_played_says_so() = runTest {
    val w = World(this, folder.root, playError = IllegalStateException("no track"))
    w.store.save(meta(1), byteArrayOf(1))
    w.check.play("rec-1.pcm")
    testScheduler.advanceUntilIdle()
    assertEquals(PLAY_FAILED_MESSAGE, w.check.message.value)
    assertNull(w.check.playing.value)
  }

  @Test
  fun delete_all_empties_the_list() = runTest {
    val w = World(this, folder.root)
    w.store.save(meta(1), byteArrayOf(1))
    testScheduler.advanceUntilIdle()
    w.check.deleteAll()
    testScheduler.advanceUntilIdle()
    assertEquals(emptyList<RecordingMeta>(), w.check.items.value)
  }

  @Test
  fun nothing_starts_while_a_question_runs() = runTest {
    val w = World(this, folder.root, askBusy = true)
    w.store.save(meta(1), byteArrayOf(1))
    testScheduler.advanceUntilIdle()
    w.check.play("rec-1.pcm")
    w.check.toggleTrial()
    w.check.deleteAll()
    testScheduler.advanceUntilIdle()
    assertEquals(emptyList<String>(), w.log)
    assertEquals(1, w.check.items.value.size)
  }

  @Test
  fun leaving_stops_only_this_screens_playback() = runTest {
    // The answer has its own player: leaving SC-12 must not reach it (4b #6).
    val w = World(this, folder.root)
    w.store.save(meta(1), byteArrayOf(1))
    w.playGate = CompletableDeferred()
    w.check.play("rec-1.pcm")
    testScheduler.advanceUntilIdle()
    w.check.leave()
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("play:[1]", "play:stop"), w.log)
    assertNull(w.check.playing.value)
  }

  @Test
  fun the_trial_button_starts_and_ends_a_trial() = runTest {
    val w = World(this, folder.root)
    w.check.toggleTrial()
    testScheduler.runCurrent()
    assertTrue(w.trial.active)
    w.check.toggleTrial()
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("cue:start", "trial:play"), w.log)
    assertEquals(TrialPhase.IDLE, w.trial.state.phase)
  }
}
