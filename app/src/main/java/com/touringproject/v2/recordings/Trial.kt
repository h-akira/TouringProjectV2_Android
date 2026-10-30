package com.touringproject.v2.recordings

import com.touringproject.v2.audio.LeaseRequest
import com.touringproject.v2.audio.PcmPlayback
import com.touringproject.v2.audio.Recorder
import com.touringproject.v2.audio.RouteControl
import com.touringproject.v2.audio.RouteLease
import com.touringproject.v2.audio.Source
import com.touringproject.v2.audio.TICK_MS
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.settings.DEFAULT_RECORD_LIMIT_MS
import com.touringproject.v2.settings.DEFAULT_START_CUE_MS
import kotlinx.coroutines.CancellationException

// The trial recording on SC-12 (slice 1 spec §5.5): recorded over the same route as a question from the screen,
// played back at once on the media stream, sent nowhere and not kept.
enum class TrialPhase { IDLE, RECORDING, PLAYING }

data class TrialState(
  val phase: TrialPhase = TrialPhase.IDLE,
  val phoneMic: Boolean = false,
  val device: InputDevice? = null,
  val error: String? = null,
)

val TRIAL_IDLE = TrialState()

const val TRIAL_MIC_MESSAGE = "マイクの使用が許可されていません。"
const val TRIAL_RECORD_MESSAGE = "録音できませんでした。"
const val TRIAL_PLAY_MESSAGE = "再生できませんでした。"

/** recordLimitMs and startCueMs are SC-11's, as for a question (US-E-03.001); cueStart sounds the start cue that long. */
class TrialDeps(
  val now: () -> Long,
  val sleep: suspend (Long) -> Unit,
  val micAllowed: () -> Boolean,
  val route: RouteControl,
  val recorder: Recorder,
  val cueStart: (durationMs: Int) -> Unit,
  val player: PcmPlayback,
  val onState: (TrialState) -> Unit,
  val recordLimitMs: () -> Long = { DEFAULT_RECORD_LIMIT_MS },
  val startCueMs: () -> Int = { DEFAULT_START_CUE_MS },
)

/** Call it from one thread only (the main thread in the app), like the ask loop. */
class TrialController(private val deps: TrialDeps) {
  var state: TrialState = TRIAL_IDLE
    private set

  private var session = 0
  private var stopRequested = false
  // From start() until its run has unwound, also after a cancel: a second run must not share the recorder.
  private var running = false
  // The try to take the audio route; a cancel calls it off while the route is still coming up.
  private var routeRequest: LeaseRequest? = null

  /** A trial is recording or playing: the intercom button ends it instead of starting a question (US-E-03.002). */
  val active: Boolean get() = state.phase != TrialPhase.IDLE


  /** The screen button or the intercom button while a trial runs: end the recording, or the playback. */
  fun stop() {
    when (state.phase) {
      TrialPhase.RECORDING -> stopRequested = true
      TrialPhase.PLAYING -> deps.player.stop()
      TrialPhase.IDLE -> Unit
    }
  }

  /** Leaving SC-12: drop whatever is going on. Whatever the run is awaiting is ignored when it returns. */
  fun cancel() {
    if (!active) return
    session += 1
    routeRequest?.callOff()
    deps.player.stop()
    set(TRIAL_IDLE)
  }

  suspend fun start() {
    if (active || running) return
    running = true
    try {
      run(++session)
    } finally {
      running = false
    }
  }

  private suspend fun run(session: Int) {
    val d = deps
    stopRequested = false
    set(TrialState(phase = TrialPhase.RECORDING))
    if (!d.micAllowed()) {
      finish(session, TrialState(error = TRIAL_MIC_MESSAGE))
      return
    }
    val request = LeaseRequest(d.route, Source.SCREEN)
    routeRequest = request
    var lease: RouteLease? = null
    var calledOff = false
    try {
      lease = request.take()
      calledOff = lease == null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // The route could not be taken: the phone's own microphone records.
    }
    // Setting up the route can take seconds; SC-12 may have been left meanwhile (React Native version's issue #10).
    if (calledOff || !isCurrent(session)) {
      lease?.release()
      return
    }
    try {
      val routed = lease?.routed == true
      set(state.copy(phoneMic = !routed))
      try {
        d.recorder.start()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        lease?.release()
        finish(session, TrialState(phoneMic = !routed, error = TRIAL_RECORD_MESSAGE))
        return
      }
      if (!isCurrent(session)) {
        d.recorder.cancel()
        lease?.release()
        return
      }
      d.cueStart(d.startCueMs())
      val startedAt = d.now()
      val limitMs = d.recordLimitMs()
      while (isCurrent(session) && !stopRequested && d.now() - startedAt < limitMs) d.sleep(TICK_MS)
      if (!isCurrent(session)) {
        d.recorder.cancel()
        lease?.release()
        return
      }
      // Read while the recorder still runs: the system only reports the input device of an active recording.
      val device = lease?.inputDevice()
      val recorded = try {
        d.recorder.stop()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        lease?.release()
        finish(session, TrialState(phoneMic = !routed, error = TRIAL_RECORD_MESSAGE))
        return
      }
      lease?.release()
      if (!isCurrent(session)) return
      playBack(session, recorded.pcm, routed, device)
    } finally {
      // Whatever ended the run, the route is let go (a lease let go of already acts once).
      lease?.release()
    }
  }

  private suspend fun playBack(session: Int, pcm: ByteArray, routed: Boolean, device: InputDevice?) {
    val d = deps
    if (pcm.isEmpty()) {
      finish(session, TrialState(phoneMic = !routed, device = device, error = TRIAL_RECORD_MESSAGE))
      return
    }
    set(state.copy(phase = TrialPhase.PLAYING, device = device))
    val error = try {
      d.player.play(pcm)
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TRIAL_PLAY_MESSAGE
    }
    // Keep showing which microphone was used after the playback.
    finish(session, state.copy(phase = TrialPhase.IDLE, error = error))
  }

  private fun isCurrent(session: Int) = session == this.session

  private fun finish(session: Int, next: TrialState) {
    if (isCurrent(session)) set(next)
  }

  private fun set(next: TrialState) {
    state = next
    deps.onState(next)
  }
}
