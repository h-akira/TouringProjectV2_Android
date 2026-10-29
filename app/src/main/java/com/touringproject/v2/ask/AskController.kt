package com.touringproject.v2.ask

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.AskApi
import com.touringproject.v2.api.AskBody
import com.touringproject.v2.api.AskResult
import com.touringproject.v2.api.Point
import com.touringproject.v2.api.PollOutcome
import com.touringproject.v2.api.pollUntilDone
import com.touringproject.v2.audio.Source
import com.touringproject.v2.audio.TICK_MS
import com.touringproject.v2.audio.pcmDurationMs
import com.touringproject.v2.location.Fix
import com.touringproject.v2.recordings.RecordingMeta
import com.touringproject.v2.settings.ReturnTiming
import com.touringproject.v2.settings.Settings
import com.touringproject.v2.settings.recordLimitMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.Base64

const val WAITING_CUE_MS = 5_000L
const val FAILED_SCREEN_MS = 5_000L

private fun reasonFor(error: Throwable): FailureReason = when ((error as? ApiError)?.kind) {
  ApiErrorKind.INVALID_KEY -> FailureReason.INVALID_KEY
  ApiErrorKind.BAD_REQUEST -> FailureReason.NO_SPEECH
  else -> FailureReason.NETWORK
}

/**
 * Orchestrates one ask: record -> send -> (return to nav) -> wait -> speak. Call it from one thread only (the main
 * thread in the app): the state is not guarded. A cancel or a newer ask does not interrupt the older one's awaits;
 * the older one notices it is no longer current when they return and does nothing more.
 */
class AskController(private val deps: AskDeps, private val scope: CoroutineScope) {
  var state: FlowState = IDLE
    private set

  /** How many asks were started; read by tests to check that one press starts exactly one ask. */
  var runsStarted = 0
    private set

  private var runId = 0
  private var current: Run? = null
  private var serviceRunning = false
  // "いま応答中です…" being said; the answer waits for it so the two never overlap.
  private var busySpeech: Deferred<Unit>? = null

  // Per-ask state: an older ask that is still unwinding must not read or change the newer one's.
  // cancelled is told when the rider ends the ask with the big button (a text ask's sheet: Android #79).
  private class Run(val id: Int, val source: Source, var returned: Boolean = false, val cancelled: () -> Unit = {})

  // meta and pcm go to SC-12 as the ask is sent; an unreached ask's recording goes again (Android #68).
  // heading is worked out once, where the recording ended, for the ask sent and the unreached one alike.
  private class Recording(
    val audio: String,
    val name: String,
    val current: Fix?,
    val heading: Double?,
    val endedAt: Long,
    val meta: RecordingMeta,
    val pcm: ByteArray,
  )

  /** The intercom button: start, stop the recording, or say that an answer is on its way. */
  fun press() {
    when {
      state.phase == Phase.RECORDING -> stopRecording()
      // The answer is already being heard; a spoken notice would only talk over it.
      state.phase == Phase.SPEAKING -> Unit
      isBusy(state) -> sayBusy()
      else -> scope.launch { run(Source.HANDSFREE) }
    }
  }

  fun tapToTalk() {
    if (!isBusy(state)) scope.launch { run(Source.SCREEN) }
  }

  /** Home's text sheet (slice 5 spec §5.3). taken is told once what became of it; nothing starts while an ask runs. */
  fun askText(text: String, taken: (TextAskOutcome) -> Unit) {
    if (isBusy(state)) {
      taken(TextAskOutcome.NOT_TAKEN)
      return
    }
    scope.launch { runText(text, taken) }
  }

  fun stopRecording() {
    if (state.phase == Phase.RECORDING) dispatch(FlowEvent.StopRecording(deps.now()))
  }

  /** The big button: end the current ask now; whatever it is awaiting is ignored when it returns. */
  fun cancel() {
    if (state.phase == Phase.IDLE) return
    current?.cancelled?.invoke()
    runId += 1
    current = null
    deps.player.stop()
    deps.speech.stop()
    dispatch(FlowEvent.Dismiss(deps.now()))
    stopService()
  }

  fun resetConversation() = deps.conversation.reset()

  suspend fun run(source: Source) {
    val d = deps
    // A previous ask may still be reading its failure message; the new ask takes over the audio.
    if (state.phase == Phase.FAILED) d.speech.stop()
    val run = Run(++runId, source)
    current = run
    runsStarted += 1
    // SC-11's values as they are when the ask starts; a change takes effect from the next ask.
    val atStart = d.settings()
    dispatch(FlowEvent.Start(source, d.now(), d.mediaVolume(), atStart.recordLimitMs))
    try {
      // Slice 2 spec §4.7: only a recording ride takes asks. Read once, so pausing meanwhile neither stops the ask nor
      // takes it out of the ride.
      val ride = d.ride()
      refusal(ride)?.let {
        fail(it, run)
        return
      }
      val rideId = (ride as RideNow.Recording).rideId
      val apiKey = d.apiKey()
      if (apiKey == null) {
        fail(FailureReason.NO_KEY, run)
        return
      }
      if (!d.micAllowed()) {
        fail(FailureReason.MIC, run)
        return
      }
      if (!isCurrent(run)) return
      val settings = d.settings()
      val api = d.api(apiKey, settings.userName)
      try {
        d.service.start()
      } catch (e: Exception) {
        // Without the foreground service the ask would stall once the screen is off; say so instead.
        d.log("service_failed", mapOf("error" to e.toString()))
        fail(FailureReason.SERVICE, run)
        return
      }
      serviceRunning = true
      val recording = record(run, atStart) ?: return
      // US-C-09: without a network the ask cannot be asked; when and where it was is kept instead (slice 4 spec §4.1).
      if (!d.online()) {
        keepUnreached(recording, rideId, run)
        return
      }
      val sent = send(recording, rideId, api, run) ?: return
      maybeReturn(ReturnTiming.SENT, settings, run)
      val result = waitForAnswer(sent, recording.name, api, run) ?: return
      speak(result, settings, run)
    } finally {
      // A newer ask (or a cancel) owns the screen state and the foreground service; leave both to it.
      if (isCurrent(run)) {
        current = null
        dispatch(FlowEvent.Dismiss(d.now()))
        stopService()
      }
    }
  }

  /**
   * A text ask (slice 5 spec §5.3): from the screen, so it never returns to the nav app. It starts as it is sent (SC-03's
   * 送っています…) and goes on like a voice ask once the Backend has taken it. Without a network it keeps nothing and
   * shows no failure: the sheet says so and keeps the text, as the rider has stopped and can send it again.
   */
  suspend fun runText(text: String, taken: (TextAskOutcome) -> Unit) {
    val d = deps
    if (state.phase == Phase.FAILED) d.speech.stop()
    var told = false
    val tell = { outcome: TextAskOutcome ->
      if (!told) {
        told = true
        taken(outcome)
      }
    }
    // Cancelled before the Backend took it, the sheet can send again at once, without waiting for the POST.
    val run = Run(++runId, Source.SCREEN, cancelled = { tell(TextAskOutcome.NOT_TAKEN) })
    current = run
    runsStarted += 1
    dispatch(FlowEvent.StartText(d.now(), d.mediaVolume()))
    try {
      val ride = d.ride()
      refusal(ride)?.let {
        tell(TextAskOutcome.NOT_TAKEN)
        fail(it, run)
        return
      }
      val rideId = (ride as RideNow.Recording).rideId
      val apiKey = d.apiKey()
      if (apiKey == null) {
        tell(TextAskOutcome.NOT_TAKEN)
        fail(FailureReason.NO_KEY, run)
        return
      }
      if (!d.online()) {
        tell(TextAskOutcome.OFFLINE)
        return
      }
      val settings = d.settings()
      val api = d.api(apiKey, settings.userName)
      try {
        d.service.start()
      } catch (e: Exception) {
        d.log("service_failed", mapOf("error" to e.toString()))
        tell(TextAskOutcome.NOT_TAKEN)
        fail(FailureReason.SERVICE, run)
        return
      }
      serviceRunning = true
      val here = d.location.latest()
      if (!isCurrent(run)) return
      val sent = sendText(text, here, d.heading(here), rideId, api, run, tell) ?: return
      val result = waitForAnswer(sent, null, api, run) ?: return
      speak(result, settings, run)
    } finally {
      // Cancelled before the Backend took it: the rider let it go, and the sheet keeps the text.
      tell(TextAskOutcome.NOT_TAKEN)
      if (isCurrent(run)) {
        current = null
        dispatch(FlowEvent.Dismiss(d.now()))
        stopService()
      }
    }
  }

  private suspend fun sendText(
    text: String,
    here: Fix?,
    heading: Double?,
    rideId: String,
    api: AskApi,
    run: Run,
    tell: (TextAskOutcome) -> Unit,
  ): SentAsk? {
    val d = deps
    val body = AskBody(
      volumeDb = d.settings().answerVolumeDb,
      text = text,
      location = here?.let { Point(it.latitude, it.longitude) },
      headingDegrees = heading,
      conversationId = d.conversation.current(d.now()),
      rideId = rideId,
    )
    val accepted = try {
      api.postAsk(body)
    } catch (e: Exception) {
      d.log("post_failed", mapOf("kind" to ((e as? ApiError)?.kind?.name ?: "unknown")))
      when (val reason = reasonFor(e)) {
        FailureReason.NETWORK -> tell(TextAskOutcome.OFFLINE)
        else -> {
          tell(TextAskOutcome.NOT_TAKEN)
          // A refused text is no speech not heard: say that it could not be answered.
          fail(if (reason == FailureReason.NO_SPEECH) FailureReason.AGENT_FAILED else reason, run)
        }
      }
      return null
    }
    // The Backend has it either way, so a conversation it went on with goes on. A text cancelled meanwhile stays not
    // taken, and the sheet keeps it (Android #79); the ask left on the Backend is taken as a voice ask's is, but a
    // conversation it started is not the one to go on with: the text is sent again, and starts or joins its own.
    if (isCurrent(run) || body.conversationId != null) d.conversation.update(accepted.conversationId, accepted.conversationExpiresAt)
    if (!isCurrent(run)) return null
    tell(TextAskOutcome.SENT)
    dispatch(FlowEvent.Sent(accepted.askId, d.now()))
    d.cues.sent()
    return SentAsk(accepted.askId, accepted.conversationId, body.conversationId == null, d.now(), here, rideId, ASK_INPUT_TEXT, text)
  }

  private fun stopService() {
    if (!serviceRunning) return
    serviceRunning = false
    deps.service.stop()
  }

  private fun dispatch(event: FlowEvent) {
    val next = reduce(state, event)
    if (next == state) return
    state = next
    deps.onState(next)
  }

  private fun isCurrent(run: Run) = run.id == runId

  // The press itself brought this app over the nav app, so go back once the rider has heard the message.
  private fun sayBusy() {
    val d = deps
    val run = current
    val said = scope.async { d.speech.say(BUSY_MESSAGE) }
    busySpeech = said
    scope.launch {
      said.await()
      if (busySpeech === said) busySpeech = null
      if (run == null || !isCurrent(run) || run.source != Source.HANDSFREE || !run.returned) return@launch
      val settings = d.settings()
      val app = settings.returnApp
      if (settings.returnEnabled && app != null) d.nav.returnTo(app.packageName)
    }
  }

  private suspend fun record(run: Run, settings: Settings): Recording? {
    val d = deps
    d.location.begin()
    val routed = try {
      d.route.start(run.source)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // e.g. the Bluetooth permission was denied: record from the phone's microphone instead of failing the ask.
      d.log("route_failed", mapOf("error" to e.toString()))
      false
    }
    // Setting up the route can take seconds; a cancel or a newer ask meanwhile owns the recorder and the route.
    if (!isCurrent(run)) {
      d.route.stop()
      d.location.end()
      return null
    }
    if (!routed) dispatch(FlowEvent.PhoneMic(d.now()))
    try {
      d.recorder.start()
    } catch (e: Exception) {
      d.route.stop()
      d.location.end()
      fail(FailureReason.MIC, run)
      return null
    }
    // After the recorder is running: the rider starts talking on this cue, so nothing said after it is lost.
    d.cues.start(settings.startCueMs)
    val startedAt = d.now()
    while (state.phase == Phase.RECORDING && isCurrent(run) && d.now() - startedAt < settings.recordLimitMs) {
      d.sleep(TICK_MS)
    }
    if (!isCurrent(run)) {
      d.recorder.cancel()
      d.route.stop()
      d.location.end()
      return null
    }
    // Read while the recorder still runs: the system only reports the input device of an active recording.
    val device = d.route.inputDevice()
    val phoneMic = state.phoneMic
    stopRecording()
    val stopped = try {
      d.recorder.stop()
    } catch (e: Exception) {
      d.route.stop()
      d.location.end()
      fail(FailureReason.MIC, run)
      return null
    }
    d.route.stop()
    // A microphone held by another app can start without delivering anything; sending nothing would be heard as "no speech".
    if (stopped.pcm.isEmpty()) {
      d.location.end()
      fail(FailureReason.MIC, run)
      return null
    }
    val endedAt = d.now()
    val meta = RecordingMeta(stopped.name, startedAt, pcmDurationMs(stopped.pcm.size), phoneMic, device, null)
    val here = d.location.end()
    val heading = d.heading(here)
    return Recording(Base64.getEncoder().encodeToString(stopped.pcm), stopped.name, here, heading, endedAt, meta, stopped.pcm)
  }

  private suspend fun send(recording: Recording, rideId: String, api: AskApi, run: Run): SentAsk? {
    val d = deps
    val here = recording.current
    val body = AskBody(
      volumeDb = d.settings().answerVolumeDb,
      audio = recording.audio,
      location = here?.let { Point(it.latitude, it.longitude) },
      headingDegrees = recording.heading,
      conversationId = d.conversation.current(d.now()),
      rideId = rideId,
    )
    // Kept for SC-12 before the POST, which may take retries: the process may die meanwhile, and an ask the Backend took
    // should keep its recording (Android #68). One that never reaches the Backend loses it again (US-C-09.001).
    d.recordings.saved(recording.meta, recording.pcm)
    val accepted = try {
      api.postAsk(body)
    } catch (e: Exception) {
      d.log("post_failed", mapOf("kind" to ((e as? ApiError)?.kind?.name ?: "unknown")))
      val reason = reasonFor(e)
      // The Backend did not take the ask in (slice 4 spec §4.1); a refusal (the key, the request) is no unreached ask.
      if (reason == FailureReason.NETWORK) {
        d.recordings.discarded(recording.name)
        keepUnreached(recording, rideId, run)
        return null
      }
      d.recordings.kept()
      fail(reason, run)
      return null
    }
    d.recordings.kept()
    // The ask exists on the Backend either way, so keep the conversation going even if it was cancelled.
    d.conversation.update(accepted.conversationId, accepted.conversationExpiresAt)
    if (!isCurrent(run)) return null
    dispatch(FlowEvent.Sent(accepted.askId, d.now()))
    d.cues.sent()
    return SentAsk(accepted.askId, accepted.conversationId, body.conversationId == null, d.now(), here, rideId)
  }

  /**
   * US-C-09: the ask never reached the Backend. Its time, place and heading are kept as an unreached ask, which is sent
   * later; the recording is dropped (US-C-09.001) and the conversation is left as it was (US-C-09.006). An ask the
   * rider cancelled meanwhile is not kept: they let it go.
   */
  private suspend fun keepUnreached(recording: Recording, rideId: String, run: Run) {
    val d = deps
    if (isCurrent(run)) {
      d.asks.keep(unreachedAsk(d.newId(), recording.endedAt, recording.current, recording.heading, rideId))
      d.log("ask_unreached", mapOf("rideId" to rideId))
    }
    fail(FailureReason.NETWORK, run)
  }

  /** recordingName is the kept recording to add what was heard to; a text ask has none. */
  private suspend fun waitForAnswer(sent: SentAsk, recordingName: String?, api: AskApi, run: Run): AskResult? {
    val d = deps
    var sinceCue = 0L
    val sleep: suspend (Long) -> Unit = { ms ->
      d.sleep(ms)
      sinceCue += ms
      if (sinceCue >= WAITING_CUE_MS && isCurrent(run)) {
        sinceCue = 0
        d.cues.waiting()
      }
    }
    val outcome = try {
      pollUntilDone({ api.getAsk(sent.askId) }, sleep, d.now) { !isCurrent(run) }
    } catch (e: Exception) {
      fail(reasonFor(e), run)
      return null
    }
    if (outcome is PollOutcome.Done && recordingName != null) outcome.result.transcript?.let { d.recordings.transcribed(recordingName, it) }
    // A result that has already arrived is kept even if the rider cancels at the same moment; a cancel while waiting
    // stops the poll (Cancelled) and keeps nothing (Android #30).
    when (outcome) {
      is PollOutcome.Done -> d.asks.keep(askRecord(sent, outcome.result, d.now()))
      PollOutcome.Timeout -> d.asks.keep(askRecord(sent, null, d.now()))
      PollOutcome.Cancelled -> Unit
    }
    if (outcome is PollOutcome.Cancelled || !isCurrent(run)) return null
    if (outcome is PollOutcome.Timeout) {
      fail(FailureReason.TIMEOUT, run)
      return null
    }
    val result = (outcome as PollOutcome.Done<AskResult>).result
    if (!readable(result)) {
      fail(failureFromServer(result.error), run)
      return null
    }
    return result
  }

  private suspend fun speak(result: AskResult, settings: Settings, run: Run) {
    val d = deps
    dispatch(FlowEvent.Answered(result.answer ?: "", d.now()))
    maybeReturn(ReturnTiming.ANSWERED, settings, run)
    val urls = result.audio.sortedBy { it.seq }.map { it.url }
    busySpeech?.await()
    var played = false
    if (urls.isNotEmpty() && isCurrent(run)) {
      try {
        d.player.play(urls)
        played = true
      } catch (e: Exception) {
        d.log("play_failed", mapOf("error" to e.toString()))
      }
    }
    if (!played && isCurrent(run) && !result.answer.isNullOrEmpty()) d.speech.say(result.answer)
    maybeReturn(ReturnTiming.SPOKEN, settings, run)
  }

  private suspend fun maybeReturn(moment: ReturnTiming, settings: Settings, run: Run) {
    if (run.returned || !isCurrent(run) || !shouldReturnNow(run.source, settings, moment)) return
    run.returned = true
    deps.nav.returnTo(settings.returnApp!!.packageName)
  }

  private suspend fun fail(reason: FailureReason, run: Run) {
    val d = deps
    if (!isCurrent(run)) return
    dispatch(FlowEvent.Fail(reason, d.now()))
    d.cues.failure()
    d.speech.say(spokenMessage(reason))
    if (!isCurrent(run)) return
    val settings = d.settings()
    if (run.source == Source.HANDSFREE) {
      // US-B-04.006: a failed answer still ends with the nav app in front.
      val app = settings.returnApp
      if (!run.returned && settings.returnEnabled && app != null) {
        run.returned = true
        d.nav.returnTo(app.packageName)
      }
      return
    }
    var waited = 0L
    while (waited < FAILED_SCREEN_MS && isCurrent(run) && state.phase == Phase.FAILED) {
      d.sleep(TICK_MS)
      waited += TICK_MS
    }
  }
}
