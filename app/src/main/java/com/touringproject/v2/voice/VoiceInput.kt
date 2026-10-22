package com.touringproject.v2.voice

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.TranscriptionApi
import com.touringproject.v2.api.TranscriptionBody
import com.touringproject.v2.api.TranscriptionResult
import com.touringproject.v2.ask.PollOutcome
import com.touringproject.v2.ask.Recorder
import com.touringproject.v2.ask.RouteControl
import com.touringproject.v2.ask.Source
import com.touringproject.v2.ask.TICK_MS
import com.touringproject.v2.ask.pcmDurationMs
import com.touringproject.v2.ask.pollUntil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Base64

// The voice input of SC-19 (slice 3 spec §7.3): recorded over the same route and with the same kind of recorder as an
// ask, sent to be turned into text only (UC-6), and fetched on the asks' schedule. What was heard is added to the text
// by the screen. The voice is kept nowhere on the device.
enum class VoicePhase { IDLE, RECORDING, TRANSCRIBING }

/** Why no text came, told under the field with what to do next (slice 3 plan 4, settled with the rider). */
enum class VoiceFailure(val message: String) {
  NO_SPEECH("声が聞き取れませんでした。もう一度話してください。"),
  TRANSCRIBE_FAILED("声を文字にできませんでした。もう一度試すか、文字で書いてください。"),
  NETWORK("通信できないため、文字にできませんでした。文字で書けば保存できます。"),
  MIC("マイクを使えませんでした。マイクの許可を確かめてください。"),
  KEY("接続キーを使えないため、文字にできませんでした。文字で書けば保存できます。"),
}

/** What was heard, numbered so the screen adds each one once. */
data class Heard(val seq: Int, val text: String)

data class VoiceState(val phase: VoicePhase = VoicePhase.IDLE, val failure: VoiceFailure? = null, val heard: Heard? = null)

val VOICE_IDLE = VoiceState()

/** The longest voice the Backend takes (slice 3 spec §6.2); the recording stops by itself then. */
const val VOICE_LIMIT_MS = 60_000L

/** The shortest the Backend takes; a shorter one is taken as nothing said. */
const val VOICE_MIN_MS = 250L

class VoiceDeps(
  val now: () -> Long,
  val sleep: suspend (Long) -> Unit,
  val micAllowed: () -> Boolean,
  /** Whether the device has a network now (slice 3 spec §7.3: without one, the voice cannot be sent). */
  val online: () -> Boolean,
  /** The client for the current key and user name, or null while there is no key or no API URL. */
  val api: () -> TranscriptionApi?,
  val route: RouteControl,
  val recorder: Recorder,
  val cueStart: () -> Unit,
  val cueFailure: () -> Unit,
  val onState: (VoiceState) -> Unit,
  val log: (event: String, data: Map<String, Any?>) -> Unit,
)

/**
 * Call it from one thread only (the main thread in the app), like the ask loop. SC-19 attaches it while it is shown:
 * the intercom button then goes here instead of starting an ask (spec §7.3). Leaving SC-19 drops whatever is going on;
 * whatever the run is awaiting is ignored when it returns.
 */
class VoiceInput(private val deps: VoiceDeps, private val scope: CoroutineScope) {
  var state: VoiceState = VOICE_IDLE
    private set

  /** True while SC-19 is shown. */
  var attached = false
    private set

  val recording: Boolean get() = state.phase == VoicePhase.RECORDING

  private var session = 0
  private var heardSeq = 0
  private var stopRequested = false
  // From a start until its run has unwound, also after a leave: a second run must not share the recorder and the route.
  private var running = false

  fun attach() {
    attached = true
  }

  fun detach() {
    attached = false
    cancel()
  }

  /** "🎤 声で入力" and the intercom button: start, or stop and send. Nothing while the text is being made. */
  fun press(source: Source) {
    when (state.phase) {
      VoicePhase.RECORDING -> stop()
      VoicePhase.TRANSCRIBING -> Unit
      VoicePhase.IDLE -> {
        if (running) return
        // The screen's button cannot be pressed without a network; the intercom button gets the failure cue.
        if (!deps.online()) {
          deps.cueFailure()
          return
        }
        running = true
        val s = ++session
        scope.launch {
          try {
            run(s, source)
          } finally {
            running = false
          }
        }
      }
    }
  }

  /** The second press, or the headset ending its voice recognition. */
  fun stop() {
    if (state.phase == VoicePhase.RECORDING) stopRequested = true
  }

  /** The screen has added what was heard. */
  fun taken(seq: Int) {
    if (state.heard?.seq == seq) set(state.copy(heard = null))
  }

  private fun cancel() {
    session += 1
    if (state != VOICE_IDLE) set(VOICE_IDLE)
  }

  private suspend fun run(s: Int, source: Source) {
    val d = deps
    stopRequested = false
    set(VoiceState(VoicePhase.RECORDING))
    if (!d.micAllowed()) return fail(s, VoiceFailure.MIC)
    try {
      d.route.start(source)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Without the intercom route the phone's own microphone records, as for an ask.
      d.log("voice_route_failed", mapOf("error" to e.toString()))
    }
    if (!isCurrent(s)) return d.route.stop()
    try {
      d.recorder.start()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      d.route.stop()
      return fail(s, VoiceFailure.MIC)
    }
    if (!isCurrent(s)) {
      d.recorder.cancel()
      return d.route.stop()
    }
    d.cueStart()
    val startedAt = d.now()
    while (isCurrent(s) && !stopRequested && d.now() - startedAt < VOICE_LIMIT_MS) d.sleep(TICK_MS)
    if (!isCurrent(s)) {
      d.recorder.cancel()
      return d.route.stop()
    }
    val pcm = try {
      d.recorder.stop().pcm
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      d.route.stop()
      return fail(s, VoiceFailure.MIC)
    }
    d.route.stop()
    if (!isCurrent(s)) return
    // A microphone held by another app can start without delivering anything.
    if (pcm.isEmpty()) return fail(s, VoiceFailure.MIC)
    if (pcmDurationMs(pcm.size) < VOICE_MIN_MS) return fail(s, VoiceFailure.NO_SPEECH)
    val api = d.api() ?: return fail(s, VoiceFailure.KEY)
    set(VoiceState(VoicePhase.TRANSCRIBING))
    val result = try {
      val accepted = api.postTranscription(TranscriptionBody(Base64.getEncoder().encodeToString(pcm)))
      if (!isCurrent(s)) return
      when (val outcome = pollUntil({ api.getTranscription(accepted.transcriptionId) }, { it.status == "processing" }, d.sleep, d.now) { !isCurrent(s) }) {
        is PollOutcome.Done -> outcome.result
        PollOutcome.Timeout -> return fail(s, VoiceFailure.NETWORK)
        PollOutcome.Cancelled -> return
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      d.log("voice_send_failed", mapOf("error" to e.toString()))
      return fail(s, failureFor(e))
    }
    if (!isCurrent(s)) return
    val text = result.text?.trim()
    when {
      result.status == "done" && !text.isNullOrEmpty() -> set(VoiceState(heard = Heard(++heardSeq, text)))
      result.status == "done" || result.failure == "no_speech" -> fail(s, VoiceFailure.NO_SPEECH)
      else -> fail(s, VoiceFailure.TRANSCRIBE_FAILED)
    }
  }

  private fun failureFor(e: Exception): VoiceFailure = when ((e as? ApiError)?.kind) {
    ApiErrorKind.INVALID_KEY -> VoiceFailure.KEY
    // The Backend refuses a voice it cannot take (too short or too long): nothing usable was said.
    ApiErrorKind.BAD_REQUEST -> VoiceFailure.NO_SPEECH
    else -> VoiceFailure.NETWORK
  }

  private fun fail(s: Int, failure: VoiceFailure) {
    if (!isCurrent(s)) return
    set(VoiceState(failure = failure))
    deps.cueFailure()
  }

  private fun isCurrent(s: Int) = s == session

  private fun set(next: VoiceState) {
    state = next
    deps.onState(next)
  }
}
