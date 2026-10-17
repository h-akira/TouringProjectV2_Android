package com.touringproject.v2.ask

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.AskAccepted
import com.touringproject.v2.api.AskApi
import com.touringproject.v2.api.AskBody
import com.touringproject.v2.api.AskResult
import com.touringproject.v2.api.AudioClip
import com.touringproject.v2.api.HeadingSample
import com.touringproject.v2.api.Point
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.location.Fix
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.ReturnTiming
import com.touringproject.v2.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val MAPS = LaunchableApp("maps", "マップ")
private val SETTINGS = Settings(userName = "rider01", returnApp = MAPS)
private val ACCEPTED = AskAccepted("a1", "c1", "2999-01-01T00:00:00Z")
private const val BUSY = "say:$BUSY_MESSAGE"
private fun done(
  status: String = "completed",
  transcript: String? = "Q",
  answer: String? = "愛鷹山です。",
  audio: List<AudioClip> = listOf(AudioClip(1, "https://audio/1.mp3")),
  error: String? = null,
) = AskResult("a1", status, transcript, answer, audio, error)
private val PROCESSING = done(status = "processing")
private val HERE = Fix(35.6812, 139.7671, 100_000, 5.0)
private val BEHIND = Fix(35.6806, 139.7671, 90_000, 5.0)

/** A fake world around the controller that records what it was asked to do, in order. */
private class Harness(
  val scope: TestScope,
  var apiKey: String? = "key",
  val settings: Settings = SETTINGS,
  var post: suspend () -> AskAccepted = { ACCEPTED },
  answers: List<Any> = listOf(done()),
  val routeOk: Boolean = true,
  var play: suspend (List<String>) -> Unit = {},
  val stopAfterMs: Long? = 3_000,
  val micAllowed: Boolean = true,
  val sayGate: CompletableDeferred<Unit>? = null,
  val serviceError: Exception? = null,
  val device: InputDevice? = InputDevice(InputDevice.Type.BLUETOOTH, "M1-S PRO"),
) {
  val log = mutableListOf<String>()
  val states = mutableListOf<FlowState>()
  val bodies = mutableListOf<AskBody>()
  val metas = mutableListOf<RecordingMeta>()
  val transcripts = mutableListOf<Pair<String, String>>()
  private val results = answers.toMutableList()
  fun now() = scope.testScheduler.currentTime

  private val api = object : AskApi {
    override suspend fun postAsk(body: AskBody): AskAccepted {
      bodies += body
      log += "post"
      return post()
    }

    override suspend fun getAsk(askId: String): AskResult {
      log += "get"
      val r = if (results.isEmpty()) done() else results.removeAt(0)
      if (r is Exception) throw r
      return r as AskResult
    }
  }

  lateinit var controller: AskController
  val deps = AskDeps(
    now = ::now,
    sleep = { ms ->
      delay(ms)
      if (stopAfterMs != null && controller.state.phase == Phase.RECORDING && now() >= stopAfterMs) controller.stopRecording()
    },
    settings = { settings },
    apiKey = { apiKey },
    micAllowed = { micAllowed },
    mediaVolume = { log += "vol:read"; MediaVolume(2, 25) },
    api = { _, _ -> api },
    conversation = ConversationState(),
    route = object : RouteControl {
      override suspend fun start(source: Source): Boolean { log += "route:${source.name.lowercase()}"; return routeOk }
      override suspend fun stop() { log += "route:stop" }
      override fun inputDevice() = device
    },
    recorder = object : Recorder {
      override suspend fun start() { log += "rec:start" }
      override suspend fun stop(): Recorded { log += "rec:stop"; return Recorded(byteArrayOf(1, 2, 3), "rec-0.pcm") }
      override suspend fun cancel() { log += "rec:cancel" }
    },
    recordings = object : RecordingSink {
      override fun saved(meta: RecordingMeta) { metas += meta }
      override fun transcribed(name: String, transcript: String) { transcripts += name to transcript }
    },
    location = object : Positions {
      override fun begin() { log += "loc:begin" }
      override fun end() = HERE to listOf(BEHIND, HERE)
    },
    service = object : KeepAlive {
      override fun start() { log += "svc:start"; serviceError?.let { throw it } }
      override fun stop() { log += "svc:stop" }
    },
    nav = object : NavReturn {
      override suspend fun returnTo(packageName: String): Boolean { log += "return:$packageName"; return true }
    },
    cues = object : Cues {
      override fun start() { log += "cue:start" }
      override fun sent() { log += "cue:sent" }
      override fun waiting() { log += "cue:waiting" }
      override fun failure() { log += "cue:failure" }
    },
    player = object : AnswerPlayer {
      override suspend fun play(urls: List<String>) { log += "play:${urls.joinToString(",")}"; this@Harness.play(urls) }
      override fun stop() { log += "play:stop" }
    },
    speech = object : Speech {
      override suspend fun say(text: String) { log += "say:$text"; sayGate?.await() }
      override fun stop() { log += "say:stop" }
    },
    onState = { states += it },
    log = { _, _ -> },
  )

  init {
    controller = AskController(deps, scope)
  }

  val phase get() = controller.state.phase

  /** Advances virtual time in small steps until the condition holds. */
  fun until(condition: () -> Boolean) {
    var steps = 0
    while (!condition()) {
      scope.testScheduler.advanceTimeBy(1)
      scope.testScheduler.runCurrent()
      check(++steps < 1_000_000) { "condition never became true" }
    }
  }
}

class AskControllerTest {
  @Test
  fun handsfree_happy_path_returns_to_nav_right_after_sending() = runTest {
    val h = Harness(this)
    h.controller.run(Source.HANDSFREE)
    assertEquals(
      listOf(
        "vol:read", "svc:start", "loc:begin", "route:handsfree", "rec:start", "cue:start", "rec:stop", "route:stop",
        "post", "cue:sent", "return:maps", "get", "play:https://audio/1.mp3", "svc:stop",
      ),
      h.log,
    )
    assertEquals(
      AskBody(
        volumeDb = 6,
        audio = "AQID",
        location = Point(35.6812, 139.7671),
        headingSample = HeadingSample(35.6806, 139.7671, "1970-01-01T00:01:30Z"),
        conversationId = null,
      ),
      h.bodies[0],
    )
    assertEquals(Phase.IDLE, h.phase)
    assertEquals(listOf(Phase.RECORDING, Phase.SENDING, Phase.WAITING, Phase.SPEAKING, Phase.IDLE), h.states.map { it.phase })
  }

  @Test
  fun screen_questions_never_return_to_nav() = runTest {
    val h = Harness(this, stopAfterMs = 1_000)
    h.controller.run(Source.SCREEN)
    assertTrue("route:screen" in h.log)
    assertFalse(h.log.any { it.startsWith("return:") })
  }

  @Test
  fun return_after_the_answer_is_spoken_when_chosen() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, settings = SETTINGS.copy(returnTiming = ReturnTiming.SPOKEN))
    h.controller.run(Source.HANDSFREE)
    assertTrue(h.log.indexOf("return:maps") > h.log.indexOf("play:https://audio/1.mp3"))
  }

  @Test
  fun recording_stops_by_itself_at_the_limit() = runTest {
    val h = Harness(this, stopAfterMs = null)
    h.controller.run(Source.HANDSFREE)
    assertTrue("post" in h.log)
    assertTrue(h.now() >= RECORD_LIMIT_MS)
  }

  @Test
  fun conversation_id_is_reused_on_the_next_question() = runTest {
    val h = Harness(this, stopAfterMs = null)
    h.controller.run(Source.HANDSFREE)
    h.controller.run(Source.HANDSFREE)
    assertEquals("c1", h.bodies[1].conversationId)
    h.controller.resetConversation()
    h.controller.run(Source.HANDSFREE)
    assertEquals(null, h.bodies[2].conversationId)
  }

  @Test
  fun phone_mic_is_flagged_when_the_intercom_route_fails() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, routeOk = false)
    h.controller.run(Source.HANDSFREE)
    assertTrue(h.states.any { it.phase == Phase.RECORDING && it.phoneMic })
    assertTrue("post" in h.log)
  }

  @Test
  fun no_key_fails_with_a_spoken_message_and_no_recording() = runTest {
    val h = Harness(this, apiKey = null)
    h.controller.run(Source.HANDSFREE)
    assertEquals(listOf("vol:read", "cue:failure", "say:接続キーを確かめてください。", "return:maps"), h.log)
  }

  @Test
  fun network_failure_on_send() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, post = { throw ApiError(ApiErrorKind.NETWORK) })
    h.controller.run(Source.HANDSFREE)
    assertTrue("say:通信できないため、質問できませんでした。" in h.log)
    assertTrue("return:maps" in h.log)
    assertFalse("get" in h.log)
  }

  @Test
  fun server_failure_is_spoken() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = listOf(done(status = "failed", error = "no_speech", answer = null, audio = emptyList())))
    h.controller.run(Source.HANDSFREE)
    assertTrue("say:聞き取れませんでした。もう一度お話しください。" in h.log)
  }

  @Test
  fun test_tts_failed_reads_answer_with_device_voice() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = listOf(done(status = "failed", error = "tts_failed", audio = emptyList())))
    h.controller.run(Source.HANDSFREE)
    assertTrue("say:愛鷹山です。" in h.log)
    assertFalse("cue:failure" in h.log)
  }

  @Test
  fun test_player_error_falls_back_to_device_voice() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, play = { throw IllegalStateException("decode") })
    h.controller.run(Source.HANDSFREE)
    assertTrue("say:愛鷹山です。" in h.log)
  }

  @Test
  fun timeout_after_two_minutes_of_processing() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = List(200) { PROCESSING })
    h.controller.run(Source.HANDSFREE)
    assertTrue("say:時間切れです。もう一度お話しください。" in h.log)
    assertTrue(h.log.count { it == "cue:waiting" } > 10)
  }

  @Test
  fun test_press_while_waiting_says_busy() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = listOf(PROCESSING, done()))
    val running = launch { h.controller.run(Source.HANDSFREE) }
    h.until { h.phase == Phase.WAITING }
    h.controller.press()
    running.join()
    assertTrue(BUSY in h.log)
    assertEquals(1, h.log.count { it == "rec:start" })
  }

  @Test
  fun second_press_stops_the_recording() = runTest {
    val h = Harness(this, stopAfterMs = null)
    val running = launch { h.controller.run(Source.HANDSFREE) }
    h.until { "rec:start" in h.log }
    h.controller.press()
    running.join()
    assertTrue(h.now() < RECORD_LIMIT_MS)
  }

  @Test
  fun cancel_while_recording_discards_the_audio() = runTest {
    val h = Harness(this, stopAfterMs = null)
    val running = launch { h.controller.run(Source.SCREEN) }
    h.until { "rec:start" in h.log }
    h.controller.cancel()
    running.join()
    assertTrue("rec:cancel" in h.log)
    assertFalse("post" in h.log)
    assertEquals(Phase.IDLE, h.phase)
  }

  @Test
  fun cancel_while_waiting_stops_polling_quietly() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = List(50) { PROCESSING })
    val running = launch { h.controller.run(Source.SCREEN) }
    h.until { h.phase == Phase.WAITING }
    h.controller.cancel()
    running.join()
    assertFalse("cue:failure" in h.log)
    assertEquals(Phase.IDLE, h.phase)
  }

  @Test
  fun test_new_run_during_failed_screen_is_not_reset() = runTest {
    var failPost = true
    val h = Harness(this, stopAfterMs = 1_000)
    h.post = { if (failPost) throw ApiError(ApiErrorKind.NETWORK) else ACCEPTED }
    val first = launch { h.controller.run(Source.SCREEN) }
    h.until { h.phase == Phase.FAILED }
    failPost = false
    val secondStart = h.log.size
    val second = launch { h.controller.run(Source.SCREEN) }
    first.join()
    second.join()
    val after = h.log.drop(secondStart)
    val played = after.indexOf("play:https://audio/1.mp3")
    assertTrue(played >= 0)
    // The first run's cleanup must not stop the service the second run is using.
    assertFalse("svc:stop" in after.take(played))
    assertEquals(Phase.IDLE, h.phase)
  }

  @Test
  fun cancel_clears_the_screen_and_stops_the_service_at_once() = runTest {
    val never = CompletableDeferred<AskAccepted>()
    val h = Harness(this, stopAfterMs = 1_000, post = { never.await() })
    val running = launch { h.controller.run(Source.SCREEN) }
    h.until { h.phase == Phase.SENDING && "post" in h.log }
    h.controller.cancel()
    assertEquals(Phase.IDLE, h.phase)
    assertTrue("svc:stop" in h.log)
    running.cancel()
  }

  @Test
  fun an_ask_cancelled_while_sending_plays_no_cue_and_does_not_return() = runTest {
    val accept = CompletableDeferred<AskAccepted>()
    val h = Harness(this, stopAfterMs = 1_000, post = { accept.await() })
    val running = launch { h.controller.run(Source.HANDSFREE) }
    h.until { "post" in h.log }
    h.controller.cancel()
    accept.complete(ACCEPTED)
    running.join()
    assertFalse("cue:sent" in h.log)
    assertFalse(h.log.any { it.startsWith("return:") })
    assertFalse("get" in h.log)
  }

  @Test
  fun a_busy_press_goes_back_to_the_nav_app_after_the_message() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = listOf(PROCESSING, done()))
    val running = launch { h.controller.run(Source.HANDSFREE) }
    h.until { h.phase == Phase.WAITING }
    h.controller.press()
    running.join()
    testScheduler.advanceUntilIdle()
    assertEquals(2, h.log.count { it == "return:maps" })
    assertTrue(h.log.lastIndexOf("return:maps") > h.log.indexOf(BUSY))
  }

  @Test
  fun a_denied_microphone_fails_without_starting_the_service() = runTest {
    val h = Harness(this, micAllowed = false)
    h.controller.run(Source.HANDSFREE)
    assertTrue("say:録音できませんでした。" in h.log)
    assertFalse("svc:start" in h.log)
  }

  @Test
  fun an_old_failure_does_not_act_on_the_next_ask() = runTest {
    val gate = CompletableDeferred<Unit>()
    var failPost = true
    val h = Harness(this, stopAfterMs = 1_000, sayGate = gate)
    h.post = { if (failPost) throw ApiError(ApiErrorKind.NETWORK) else ACCEPTED }
    val first = launch { h.controller.run(Source.SCREEN) }
    h.until { h.phase == Phase.FAILED }
    failPost = false
    h.controller.press()
    h.until { h.phase == Phase.RECORDING }
    gate.complete(Unit)
    first.join()
    h.until { h.phase == Phase.IDLE }
    val returns = h.log.withIndex().filter { it.value == "return:maps" }
    assertEquals(1, returns.size)
    assertTrue(returns[0].index > h.log.lastIndexOf("post"))
  }

  @Test
  fun the_chosen_answer_volume_goes_with_every_question() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, settings = SETTINGS.copy(answerVolumeDb = 3))
    h.controller.run(Source.HANDSFREE)
    assertEquals(3, h.bodies[0].volumeDb)
  }

  @Test
  fun a_foreground_service_that_cannot_start_fails_out_loud() = runTest {
    val h = Harness(this, serviceError = IllegalStateException("ForegroundServiceStartNotAllowedException"))
    h.controller.run(Source.HANDSFREE)
    assertEquals(
      listOf("vol:read", "svc:start", "cue:failure", "say:準備できなかったため、質問できませんでした。もう一度お試しください。", "return:maps"),
      h.log,
    )
    assertTrue(h.states.any { it.phase == Phase.FAILED && it.failure == FailureReason.SERVICE })
    assertEquals(Phase.IDLE, h.phase)
  }

  @Test
  fun the_kept_recording_is_described_for_sc12() = runTest {
    val h = Harness(this)
    h.controller.run(Source.HANDSFREE)
    // 3 bytes of PCM is shorter than a millisecond.
    assertEquals(listOf(RecordingMeta("rec-0.pcm", 0, 0, false, InputDevice(InputDevice.Type.BLUETOOTH, "M1-S PRO"), null)), h.metas)
    assertEquals(listOf("rec-0.pcm" to "Q"), h.transcripts)
  }

  @Test
  fun a_phone_mic_recording_is_marked_so() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, routeOk = false, device = InputDevice(InputDevice.Type.BUILTIN, "Pixel"))
    h.controller.run(Source.HANDSFREE)
    assertTrue(h.metas[0].phoneMic)
    assertEquals(InputDevice(InputDevice.Type.BUILTIN, "Pixel"), h.metas[0].device)
  }

  @Test
  fun what_was_heard_is_kept_even_when_the_answer_fails() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = listOf(done(status = "failed", transcript = "", answer = null, audio = emptyList(), error = "no_speech")))
    h.controller.run(Source.HANDSFREE)
    assertEquals(listOf("rec-0.pcm" to ""), h.transcripts)
  }

  @Test
  fun the_answer_waits_until_the_busy_message_has_been_said() = runTest {
    val gate = CompletableDeferred<Unit>()
    val h = Harness(this, stopAfterMs = 1_000, sayGate = gate, answers = listOf(PROCESSING, done()))
    val running = launch { h.controller.run(Source.HANDSFREE) }
    h.until { h.phase == Phase.WAITING }
    h.controller.press()
    // The answer arrives while "いま応答中です…" is still being said.
    h.until { h.phase == Phase.SPEAKING }
    testScheduler.advanceTimeBy(5_000)
    testScheduler.runCurrent()
    assertFalse(h.log.any { it.startsWith("play:") })
    gate.complete(Unit)
    running.join()
    val busy = h.log.indexOf(BUSY)
    assertTrue(busy >= 0)
    assertTrue(h.log.indexOf("play:https://audio/1.mp3") > busy)
  }

  @Test
  fun a_press_while_the_answer_is_read_out_says_nothing_over_it() = runTest {
    val endPlay = CompletableDeferred<Unit>()
    val h = Harness(this, stopAfterMs = 1_000, play = { endPlay.await() })
    val running = launch { h.controller.run(Source.HANDSFREE) }
    h.until { h.phase == Phase.SPEAKING && h.log.any { it.startsWith("play:") } }
    h.controller.press()
    testScheduler.runCurrent()
    endPlay.complete(Unit)
    running.join()
    assertFalse(h.log.any { it.startsWith("say:") })
    assertEquals(1, h.log.count { it == "rec:start" })
  }

  @Test
  fun the_media_volume_is_read_before_the_intercom_route_takes_over() = runTest {
    // While SCO is up, the music stream reports the call device's volume instead.
    val h = Harness(this, stopAfterMs = 1_000)
    h.controller.run(Source.HANDSFREE)
    assertTrue(h.log.indexOf("vol:read") in 0 until h.log.indexOf("route:handsfree"))
    assertEquals(Phase.RECORDING, h.states[0].phase)
    assertEquals(MediaVolume(2, 25), h.states[0].mediaVolume)
  }

  @Test
  fun each_run_is_counted_once() = runTest {
    val h = Harness(this, apiKey = null)
    h.controller.press()
    testScheduler.advanceUntilIdle()
    assertEquals(1, h.controller.runsStarted)
  }
}
