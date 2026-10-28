package com.touringproject.v2.ask

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.AskAccepted
import com.touringproject.v2.api.AskApi
import com.touringproject.v2.api.AskBody
import com.touringproject.v2.api.AskResult
import com.touringproject.v2.api.AudioClip
import com.touringproject.v2.api.Point
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.location.Fix
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.pause
import com.touringproject.v2.settings.DEFAULT_RECORD_LIMIT_MS
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.ReturnTiming
import com.touringproject.v2.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

/** A fake world around the controller that records what it was asked to do, in order. */
private class Harness(
  val scope: TestScope,
  var apiKey: String? = "key",
  val settings: Settings = SETTINGS,
  /** Read at every use, so a test can change the settings between asks. */
  val settingsOf: () -> Settings = { settings },
  var post: suspend () -> AskAccepted = { ACCEPTED },
  answers: List<Any> = listOf(done()),
  val routeOk: Boolean = true,
  var routeStart: (suspend () -> Boolean)? = null,
  var play: suspend (List<String>) -> Unit = {},
  val stopAfterMs: Long? = 3_000,
  val micAllowed: Boolean = true,
  val sayGate: CompletableDeferred<Unit>? = null,
  val serviceError: Exception? = null,
  val device: InputDevice? = InputDevice(InputDevice.Type.BLUETOOTH, "M1-S PRO"),
  val pcm: ByteArray = byteArrayOf(1, 2, 3),
  /** Asks start only while a ride records (slice 2 spec §4.7); most tests ride. */
  var ride: RideNow = RideNow.Recording("r1"),
  /** Whether the device has a network when the recording ends (slice 4 spec §4.1). */
  var online: Boolean = true,
  /** What the ride's route gives as the heading (slice 5 spec §4). */
  var heading: Double? = 12.5,
  /** The device's last known fix, where a text ask is (slice 5 spec §4.1). */
  var latest: Fix? = HERE,
) {
  /** What each text ask told the sheet. */
  val outcomes = mutableListOf<TextAskOutcome>()
  /** Where and when the heading was asked for. */
  val headingAsked = mutableListOf<Pair<Fix?, Long>>()
  /** How long each start cue was asked to sound. */
  val startCues = mutableListOf<Int>()
  val log = mutableListOf<String>()
  val states = mutableListOf<FlowState>()
  val bodies = mutableListOf<AskBody>()
  /** The recordings SC-12 keeps now: saved and not discarded again. */
  val metas = mutableListOf<RecordingMeta>()
  val keptPcm = mutableListOf<ByteArray>()
  val discarded = mutableListOf<String>()
  val transcripts = mutableListOf<Pair<String, String>>()
  val kept = mutableListOf<AskRecord>()
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
    settings = settingsOf,
    apiKey = { apiKey },
    micAllowed = { micAllowed },
    mediaVolume = { log += "vol:read"; MediaVolume(2, 25) },
    api = { _, _ -> api },
    conversation = ConversationState(),
    route = object : RouteControl {
      override suspend fun start(source: Source): Boolean { log += "route:${source.name.lowercase()}"; return routeStart?.invoke() ?: routeOk }
      override suspend fun stop() { log += "route:stop" }
      override fun inputDevice() = device
    },
    recorder = object : Recorder {
      override suspend fun start() { log += "rec:start" }
      override suspend fun stop(): Recorded { log += "rec:stop"; return Recorded(pcm, "rec-0.pcm") }
      override suspend fun cancel() { log += "rec:cancel" }
    },
    recordings = object : RecordingSink {
      override fun saved(meta: RecordingMeta, pcm: ByteArray) { metas += meta; keptPcm += pcm }
      override fun discarded(name: String) {
        discarded += name
        val at = metas.indexOfFirst { it.name == name }
        if (at >= 0) { metas.removeAt(at); keptPcm.removeAt(at) }
      }
      override fun transcribed(name: String, transcript: String) { transcripts += name to transcript }
    },
    asks = { kept += it },
    online = { online },
    newId = { "u1" },
    ride = { ride },
    location = object : Positions {
      override fun begin() { log += "loc:begin" }
      override fun end() = HERE
      override suspend fun latest(): Fix? { log += "loc:latest"; return latest }
    },
    heading = { here, at -> headingAsked += here to at; heading },
    service = object : KeepAlive {
      override fun start() { log += "svc:start"; serviceError?.let { throw it } }
      override fun stop() { log += "svc:stop" }
    },
    nav = object : NavReturn {
      override suspend fun returnTo(packageName: String): Boolean { log += "return:$packageName"; return true }
    },
    cues = object : Cues {
      override fun start(durationMs: Int) { log += "cue:start"; startCues += durationMs }
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

  suspend fun askText(text: String) = controller.runText(text) { outcomes += it }

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
        headingDegrees = 12.5,
        conversationId = null,
        rideId = "r1",
      ),
      h.bodies[0],
    )
    // Slice 5 spec §4.1: the heading is taken where and when the recording ended.
    assertEquals(listOf<Pair<Fix?, Long>>(HERE to 3_000L), h.headingAsked)
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
    assertTrue(h.now() >= DEFAULT_RECORD_LIMIT_MS)
    assertEquals(listOf(1_500), h.startCues)
    assertEquals(DEFAULT_RECORD_LIMIT_MS, h.states.first().recordLimitMs)
  }

  // Slice 5 spec §6: SC-11's limit and start cue as they are when the ask starts; SC-03 counts down from that limit.
  @Test
  fun the_recording_limit_and_start_cue_come_from_sc_11() = runTest {
    var settings = SETTINGS.copy(recordLimitSeconds = 10, startCueMs = 0)
    val h = Harness(this, stopAfterMs = null, settingsOf = { settings })
    // How long each ask recorded: from its start to its SENDING.
    fun recorded() = h.states.filter { it.phase == Phase.RECORDING || it.phase == Phase.SENDING }
      .zipWithNext { a, b -> if (a.phase == Phase.RECORDING && b.phase == Phase.SENDING) b.phaseStartedAt - a.phaseStartedAt else null }
      .filterNotNull()
    h.controller.run(Source.HANDSFREE)
    assertEquals(listOf(10_000L), recorded())
    assertEquals(listOf(0), h.startCues)
    assertEquals(10_000L, h.states.first().recordLimitMs)
    settings = SETTINGS.copy(recordLimitSeconds = 45, startCueMs = 3_000)
    h.controller.run(Source.HANDSFREE)
    assertEquals(listOf(10_000L, 45_000L), recorded())
    assertEquals(listOf(0, 3_000), h.startCues)
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
    assertTrue(h.now() < DEFAULT_RECORD_LIMIT_MS)
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
    assertEquals(listOf(listOf<Byte>(1, 2, 3)), h.keptPcm.map { it.toList() })
    assertEquals(listOf("rec-0.pcm" to "Q"), h.transcripts)
  }

  @Test
  fun a_recording_with_nothing_in_it_fails_as_a_microphone_problem() = runTest {
    // e.g. another app holds the microphone (issue #1): "録音できませんでした", not "聞き取れませんでした".
    val h = Harness(this, pcm = ByteArray(0))
    h.controller.run(Source.HANDSFREE)
    assertFalse("post" in h.log)
    assertTrue(h.states.any { it.phase == Phase.FAILED && it.failure == FailureReason.MIC })
    assertTrue("say:録音できませんでした。" in h.log)
    assertTrue(h.metas.isEmpty())
    assertEquals(Phase.IDLE, h.phase)
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

  @Test
  fun a_cancel_during_route_setup_does_not_start_the_recording() = runTest {
    val gate = CompletableDeferred<Boolean>()
    val h = Harness(this, stopAfterMs = null, routeStart = { gate.await() })
    val running = launch { h.controller.run(Source.SCREEN) }
    h.until { "route:screen" in h.log }
    h.controller.cancel()
    gate.complete(true)
    running.join()
    assertFalse("rec:start" in h.log)
    assertFalse("cue:start" in h.log)
    assertTrue("route:stop" in h.log)
  }

  @Test
  fun a_route_that_throws_falls_back_to_the_phone_mic() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, routeStart = { throw SecurityException("BLUETOOTH_CONNECT") })
    h.controller.run(Source.HANDSFREE)
    assertTrue(h.states.any { it.phase == Phase.RECORDING && it.phoneMic })
    assertTrue("post" in h.log)
  }

  @Test
  fun an_answered_ask_is_kept_with_where_it_was_asked() = runTest {
    val h = Harness(this, stopAfterMs = 1_000)
    h.controller.run(Source.HANDSFREE)
    val kept = h.kept.single()
    assertEquals("a1", kept.askId)
    assertEquals(AskOutcome.ANSWERED, kept.outcome)
    assertEquals("愛鷹山です。", kept.answer)
    assertEquals(HERE.latitude, kept.latitude!!, 0.0)
    assertEquals("c1", kept.conversationId)
    assertTrue(kept.newConversation)
    assertEquals("r1", kept.rideId)
  }

  @Test
  fun the_next_ask_of_the_conversation_does_not_start_a_new_one() = runTest {
    val h = Harness(this, stopAfterMs = null)
    h.controller.run(Source.HANDSFREE)
    h.controller.run(Source.HANDSFREE)
    assertEquals(listOf(true, false), h.kept.map { it.newConversation })
  }

  @Test
  fun a_failure_the_backend_reports_is_kept_with_its_reason() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = listOf(done(status = "failed", error = "no_speech", answer = null, audio = emptyList())))
    h.controller.run(Source.HANDSFREE)
    val kept = h.kept.single()
    assertEquals(AskOutcome.FAILED, kept.outcome)
    assertEquals("no_speech", kept.failure)
    assertEquals(null, kept.answeredAt)
  }

  @Test
  fun an_ask_that_timed_out_is_kept_as_a_timeout() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = List(200) { PROCESSING })
    h.controller.run(Source.HANDSFREE)
    assertEquals(TIMEOUT_FAILURE, h.kept.single().failure)
  }

  // Slice 4 spec §4.1: the network failed on the way, so the Backend never took the ask in; it is kept as unreached.
  @Test
  fun an_ask_the_backend_never_took_in_is_kept_as_unreached_without_its_recording() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, post = { throw ApiError(ApiErrorKind.NETWORK) })
    h.controller.run(Source.HANDSFREE)
    val kept = h.kept.single()
    assertEquals(AskOutcome.OFFLINE, kept.outcome)
    assertEquals("u1", kept.askId)
    assertEquals("r1", kept.rideId)
    assertTrue(h.metas.isEmpty())
    assertTrue("say:通信できないため、質問できませんでした。" in h.log)
  }

  // US-C-09.001: with no network when the recording ends, nothing is sent; when and where it was is kept.
  @Test
  fun without_a_network_the_ask_is_not_sent_and_is_kept_as_unreached() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, online = false)
    h.controller.run(Source.HANDSFREE)
    assertFalse("post" in h.log)
    val kept = h.kept.single()
    assertEquals(AskOutcome.OFFLINE, kept.outcome)
    assertEquals(1_000L, kept.askedAt)
    assertEquals(1_000L, kept.updatedAt)
    assertEquals(HERE.latitude, kept.latitude!!, 0.0)
    assertEquals(12.5, kept.headingDegrees!!, 0.0)
    assertEquals(null, kept.conversationId)
    assertFalse(kept.newConversation)
    assertFalse(kept.sent)
    assertTrue(h.metas.isEmpty())
    assertEquals(
      listOf("cue:failure", "say:通信できないため、質問できませんでした。", "return:maps", "svc:stop"),
      h.log.dropWhile { it != "route:stop" }.drop(1),
    )
  }

  // US-C-09.006: an unreached ask joins no conversation, so the next ask carries on the one before it.
  @Test
  fun an_unreached_ask_leaves_the_conversation_as_it_was() = runTest {
    val h = Harness(this, stopAfterMs = null)
    h.controller.run(Source.HANDSFREE)
    h.online = false
    h.controller.run(Source.HANDSFREE)
    h.online = true
    h.controller.run(Source.HANDSFREE)
    // Only the first and the third were sent; the third goes on with the first's conversation.
    assertEquals(listOf(null, "c1"), h.bodies.map { it.conversationId })
    assertEquals(listOf(true, false, false), h.kept.map { it.newConversation })
  }

  // A 5xx after the retries is a network failure for the rider: the Backend did not take the ask in.
  @Test
  fun a_server_failure_on_send_keeps_an_unreached_ask() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, post = { throw ApiError(ApiErrorKind.SERVER, "HTTP 503") })
    h.controller.run(Source.HANDSFREE)
    assertEquals(AskOutcome.OFFLINE, h.kept.single().outcome)
  }

  // A refusal is no unreached ask: the network worked. The recording stays for SC-12, as before.
  @Test
  fun a_refused_key_keeps_no_ask_but_keeps_the_recording() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, post = { throw ApiError(ApiErrorKind.INVALID_KEY) })
    h.controller.run(Source.HANDSFREE)
    assertTrue(h.kept.isEmpty())
    assertEquals(1, h.metas.size)
  }

  // Android #68: the recording is kept before the POST and its retries, so a process that dies meanwhile does not lose
  // the recording of an ask the Backend took; one whose send failed on the network goes again.
  @Test
  fun the_recording_is_kept_before_the_post_and_goes_again_when_the_network_fails() = runTest {
    var keptAtPost = -1
    lateinit var h: Harness
    h = Harness(this, stopAfterMs = 1_000, post = { keptAtPost = h.metas.size; ACCEPTED })
    h.controller.run(Source.HANDSFREE)
    assertEquals(1, keptAtPost)
    assertEquals(1, h.metas.size)
    assertTrue(h.discarded.isEmpty())
    val failed = Harness(this, stopAfterMs = 1_000)
    failed.post = { keptAtPost = failed.metas.size; throw ApiError(ApiErrorKind.NETWORK) }
    failed.controller.run(Source.HANDSFREE)
    assertEquals(1, keptAtPost)
    assertEquals(listOf("rec-0.pcm"), failed.discarded)
    assertTrue(failed.metas.isEmpty())
  }

  // The rider cancelled while the ask was being sent: they let it go, so it is not kept even when the send fails.
  @Test
  fun an_ask_cancelled_while_sending_is_not_kept_as_unreached() = runTest {
    val h = Harness(this, stopAfterMs = 1_000)
    h.post = {
      delay(5_000)
      throw ApiError(ApiErrorKind.NETWORK)
    }
    val running = launch { h.controller.run(Source.SCREEN) }
    h.until { h.phase == Phase.SENDING }
    h.controller.cancel()
    running.join()
    assertTrue(h.kept.isEmpty())
  }

  @Test
  fun an_ask_cancelled_while_waiting_is_not_kept() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, answers = List(200) { PROCESSING })
    launch { h.controller.run(Source.HANDSFREE) }
    h.until { h.phase == Phase.WAITING }
    h.controller.cancel()
    advanceUntilIdle()
    assertTrue(h.kept.isEmpty())
  }

  @Test
  fun an_ask_started_while_a_ride_records_goes_with_the_ride() = runTest {
    val h = Harness(this, stopAfterMs = 1_000, ride = RideNow.Recording("r7"))
    h.controller.run(Source.HANDSFREE)
    assertEquals("r7", h.bodies.single().rideId)
    assertEquals("r7", h.kept.single().rideId)
  }

  @Test
  fun the_ride_is_read_when_the_ask_starts_so_a_pause_meanwhile_neither_stops_nor_drops_it() = runTest {
    val h = Harness(this, stopAfterMs = 1_000)
    launch { h.controller.run(Source.HANDSFREE) }
    h.until { h.phase == Phase.RECORDING }
    h.ride = RideNow.Paused
    advanceUntilIdle()
    assertEquals("r1", h.bodies.single().rideId)
    assertEquals(AskOutcome.ANSWERED, h.kept.single().outcome)
  }

  // Slice 2 spec §4.7: no ride recording, no ask. The rider hears why, and the nav app comes back as after any failure.
  @Test
  fun without_a_recording_ride_the_button_starts_nothing_and_says_so() = runTest {
    val h = Harness(this, ride = RideNow.NotRecording)
    h.controller.run(Source.HANDSFREE)
    assertEquals(listOf("vol:read", "cue:failure", "say:走行を記録していないので、質問できません。", "return:maps"), h.log)
    assertTrue(h.states.any { it.phase == Phase.FAILED && it.failure == FailureReason.NOT_RECORDING })
    assertTrue(h.kept.isEmpty())
  }

  @Test
  fun while_the_ride_is_paused_the_button_starts_nothing_and_says_how_to_ask() = runTest {
    val h = Harness(this, ride = RideNow.Paused)
    h.controller.run(Source.HANDSFREE)
    assertEquals(listOf("vol:read", "cue:failure", "say:一時停止中は質問できません。再開すると質問できます。", "return:maps"), h.log)
    assertTrue(h.states.any { it.phase == Phase.FAILED && it.failure == FailureReason.PAUSED })
  }

  // Android #48: from the screen (normally its button cannot be pressed then), a refused ask shows why on SC-03 and stays
  // in this app.
  @Test
  fun a_screen_ask_without_a_recording_ride_is_refused_on_screen_and_stays_here() = runTest {
    for ((ride, reason) in listOf(RideNow.NotRecording to FailureReason.NOT_RECORDING, RideNow.Paused to FailureReason.PAUSED)) {
      val h = Harness(this, ride = ride)
      h.controller.run(Source.SCREEN)
      assertTrue(h.states.any { it.phase == Phase.FAILED && it.failure == reason && it.source == Source.SCREEN })
      assertFalse(h.log.any { it.startsWith("return:") || it == "svc:start" || it == "rec:start" })
      assertTrue(h.kept.isEmpty())
    }
  }
}

// Slice 5 spec §5: asking by text from Home.
class TextAskTest {
  // §5.3: no recording; it starts as it is sent, waits for the answer, reads it out, and never goes back to the nav app.
  @Test
  fun a_text_ask_starts_at_sending_and_is_read_out_without_returning() = runTest {
    val h = Harness(this, answers = listOf(done(transcript = null)))
    h.askText("右手に見える山は何？")
    assertEquals(listOf("svc:start", "loc:latest", "post", "cue:sent", "get", "play:https://audio/1.mp3", "svc:stop"), h.log)
    assertEquals(
      AskBody(volumeDb = 6, text = "右手に見える山は何？", location = Point(35.6812, 139.7671), headingDegrees = 12.5, conversationId = null, rideId = "r1"),
      h.bodies.single(),
    )
    assertEquals(listOf<Pair<Fix?, Long>>(HERE to 0L), h.headingAsked)
    assertEquals(listOf(Phase.SENDING, Phase.WAITING, Phase.SPEAKING, Phase.IDLE), h.states.map { it.phase })
    assertEquals(Source.SCREEN, h.states.first().source)
    assertEquals(listOf(TextAskOutcome.SENT), h.outcomes)
    // §5.4: kept as a text ask, with what was typed as its question.
    val kept = h.kept.single()
    assertEquals(ASK_INPUT_TEXT, kept.input)
    assertEquals("右手に見える山は何？", kept.transcript)
    assertEquals(AskOutcome.ANSWERED, kept.outcome)
    assertTrue(h.metas.isEmpty())
  }

  // The conversation goes on across voice and text asks alike.
  @Test
  fun a_text_ask_carries_the_conversation_on() = runTest {
    val h = Harness(this, stopAfterMs = 1_000)
    h.controller.run(Source.HANDSFREE)
    h.askText("その山の高さは？")
    assertEquals(listOf(null, "c1"), h.bodies.map { it.conversationId })
  }

  // §5.3: without a network nothing is sent, nothing is kept (no unreached ask) and SC-03 shows no failure.
  @Test
  fun without_a_network_a_text_ask_keeps_nothing_and_tells_the_sheet() = runTest {
    val h = Harness(this, online = false)
    h.askText("右手に見える山は何？")
    assertEquals(listOf(TextAskOutcome.OFFLINE), h.outcomes)
    assertFalse("post" in h.log)
    assertTrue(h.kept.isEmpty())
    assertTrue(h.states.none { it.phase == Phase.FAILED })
    assertFalse(h.log.any { it.startsWith("say:") || it == "cue:failure" })
    assertEquals(Phase.IDLE, h.phase)
  }

  // A network failure on the way is the same: the Backend never took it.
  @Test
  fun a_network_failure_on_send_keeps_nothing_and_tells_the_sheet() = runTest {
    val h = Harness(this, post = { throw ApiError(ApiErrorKind.SERVER, "HTTP 503") })
    h.askText("右手に見える山は何？")
    assertEquals(listOf(TextAskOutcome.OFFLINE), h.outcomes)
    assertTrue(h.kept.isEmpty())
    assertTrue(h.states.none { it.phase == Phase.FAILED })
    assertEquals(listOf("svc:start", "loc:latest", "post", "svc:stop"), h.log)
  }

  // Refusals show why on SC-03 (US-B-06.004), and the sheet keeps the text.
  @Test
  fun a_refused_text_ask_says_why_on_screen_and_the_sheet_keeps_the_text() = runTest {
    val cases = listOf(
      Harness(this, ride = RideNow.NotRecording) to FailureReason.NOT_RECORDING,
      Harness(this, ride = RideNow.Paused) to FailureReason.PAUSED,
      Harness(this, apiKey = null) to FailureReason.NO_KEY,
      Harness(this, post = { throw ApiError(ApiErrorKind.INVALID_KEY) }) to FailureReason.INVALID_KEY,
      Harness(this, post = { throw ApiError(ApiErrorKind.BAD_REQUEST) }) to FailureReason.AGENT_FAILED,
      Harness(this, serviceError = IllegalStateException("background")) to FailureReason.SERVICE,
    )
    for ((h, reason) in cases) {
      h.askText("右手に見える山は何？")
      assertEquals(listOf(TextAskOutcome.NOT_TAKEN), h.outcomes)
      assertTrue("$reason", h.states.any { it.phase == Phase.FAILED && it.failure == reason })
      assertFalse(h.log.any { it.startsWith("return:") })
      assertTrue(h.kept.isEmpty())
    }
  }

  // §5.3: cancelled while waiting for the answer, it is kept nowhere, as a voice ask.
  @Test
  fun a_text_ask_cancelled_while_waiting_is_not_kept() = runTest {
    val h = Harness(this, answers = listOf(PROCESSING, PROCESSING, PROCESSING))
    val running = launch { h.askText("右手に見える山は何？") }
    h.until { h.phase == Phase.WAITING }
    h.controller.cancel()
    running.join()
    advanceUntilIdle()
    assertEquals(listOf(TextAskOutcome.SENT), h.outcomes)
    assertTrue(h.kept.isEmpty())
  }

  // §4.1: without a fresh position, the text ask goes without a place, and the heading is asked without one.
  @Test
  fun without_a_position_a_text_ask_goes_without_a_place() = runTest {
    val h = Harness(this, latest = null, heading = null)
    h.askText("右手に見える山は何？")
    assertEquals(null, h.bodies.single().location)
    assertEquals(null, h.bodies.single().headingDegrees)
    assertEquals(listOf<Pair<Fix?, Long>>(null to 0L), h.headingAsked)
  }

  @Test
  fun nothing_starts_while_an_ask_runs() = runTest {
    val h = Harness(this, stopAfterMs = null)
    launch { h.controller.run(Source.HANDSFREE) }
    h.until { h.phase == Phase.RECORDING }
    h.controller.askText("右手に見える山は何？") { h.outcomes += it }
    assertEquals(listOf(TextAskOutcome.NOT_TAKEN), h.outcomes)
    h.controller.cancel()
    advanceUntilIdle()
    assertTrue(h.bodies.isEmpty())
  }

  @Test
  fun the_sheet_takes_500_characters_and_no_blank_question() {
    assertEquals(500, TEXT_ASK_MAX)
    assertEquals(500, cutTextAsk("あ".repeat(600)).length)
    val emoji = "😀".repeat(501)
    assertEquals(500, cutTextAsk(emoji).codePointCount(0, cutTextAsk(emoji).length))
    assertEquals(497, textAskLeft("山は何"))
    assertEquals(null, textQuestion(" \n "))
    assertEquals("山は何？", textQuestion("  山は何？ \n"))
  }
}

class AskRecordTest {
  private val sent = SentAsk("a1", "c1", true, 5_000, Fix(35.0, 139.0, 4_000, 5.0), "r1")

  @Test
  fun the_backend_time_of_the_ask_is_kept_and_the_device_time_stands_in_without_it() {
    val result = AskResult("a1", "completed", "Q", "A", askedAt = "2026-10-18T01:02:03.456Z", address = "御殿場市", headingDegrees = 90.0)
    val kept = askRecord(sent, result, 9_000)
    assertEquals(java.time.Instant.parse("2026-10-18T01:02:03.456Z").toEpochMilli(), kept.askedAt)
    assertEquals(9_000L, kept.answeredAt)
    assertEquals("御殿場市", kept.address)
    assertEquals(5_000L, askRecord(sent, result.copy(askedAt = null), 9_000).askedAt)
  }

  @Test
  fun text_whose_speech_failed_is_an_answer_and_a_failure_without_a_reason_is_the_agents() {
    assertEquals(AskOutcome.ANSWERED, askRecord(sent, AskResult("a1", "failed", "Q", "A", error = "tts_failed"), 9_000).outcome)
    val failed = askRecord(sent, AskResult("a1", "failed", "Q", null), 9_000)
    assertEquals(AskOutcome.FAILED, failed.outcome)
    assertEquals("agent_failed", failed.failure)
  }

  // Slice 5 spec §5.4: a text ask keeps what was typed, also when it timed out without a result.
  @Test
  fun a_text_ask_is_kept_as_text_with_what_was_typed() {
    val text = sent.copy(input = ASK_INPUT_TEXT, question = "山は何？")
    assertEquals(ASK_INPUT_TEXT to "山は何？", askRecord(text, null, 9_000).let { it.input to it.transcript })
    assertEquals(ASK_INPUT_VOICE, askRecord(sent, null, 9_000).input)
  }
}

class RideNowTest {
  private val ride = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS)

  @Test
  fun only_a_recording_ride_takes_asks() {
    assertEquals(RideNow.Recording("r1"), rideNow(ride))
    assertEquals(RideNow.Paused, rideNow(ride.pause(5_000)))
    assertEquals(RideNow.NotRecording, rideNow(null))
  }

  @Test
  fun only_a_recording_ride_has_no_refusal() {
    assertEquals(null, refusal(RideNow.Recording("r1")))
    assertEquals(FailureReason.PAUSED, refusal(RideNow.Paused))
    assertEquals(FailureReason.NOT_RECORDING, refusal(RideNow.NotRecording))
  }
}
