package com.touringproject.v2.ask

import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.settings.ReturnTiming
import com.touringproject.v2.settings.Settings

// The ask loop as a pure state machine (slice 1 spec §5.2). Screens, cues and returning to the nav app read this state.
enum class Source { HANDSFREE, SCREEN }

enum class Phase { IDLE, RECORDING, SENDING, WAITING, SPEAKING, FAILED }

enum class FailureReason { NO_SPEECH, TRANSCRIBE_FAILED, AGENT_FAILED, TTS_FAILED, TIMEOUT, NETWORK, INVALID_KEY, NO_KEY, MIC, SERVICE }

data class FlowState(
  val phase: Phase = Phase.IDLE,
  val source: Source? = null,
  val askId: String? = null,
  val answer: String? = null,
  val failure: FailureReason? = null,
  val phoneMic: Boolean = false,
  val phaseStartedAt: Long = 0,
  // Read when the ask starts, before the intercom route switches the music stream to the call device's volume.
  val mediaVolume: MediaVolume? = null,
)

val IDLE = FlowState()

sealed interface FlowEvent {
  val now: Long
  data class Start(val source: Source, override val now: Long, val mediaVolume: MediaVolume? = null) : FlowEvent
  data class PhoneMic(override val now: Long) : FlowEvent
  data class StopRecording(override val now: Long) : FlowEvent
  data class Sent(val askId: String, override val now: Long) : FlowEvent
  data class Answered(val answer: String, override val now: Long) : FlowEvent
  data class Spoken(override val now: Long) : FlowEvent
  data class Fail(val reason: FailureReason, override val now: Long) : FlowEvent
  data class Dismiss(override val now: Long) : FlowEvent
}

fun reduce(state: FlowState, event: FlowEvent): FlowState = when (event) {
  is FlowEvent.Start ->
    if (state.phase == Phase.IDLE || state.phase == Phase.FAILED) {
      FlowState(phase = Phase.RECORDING, source = event.source, phaseStartedAt = event.now, mediaVolume = event.mediaVolume)
    } else state
  is FlowEvent.PhoneMic -> if (state.phase == Phase.RECORDING) state.copy(phoneMic = true) else state
  is FlowEvent.StopRecording ->
    if (state.phase == Phase.RECORDING) state.copy(phase = Phase.SENDING, phaseStartedAt = event.now) else state
  is FlowEvent.Sent ->
    if (state.phase == Phase.SENDING) state.copy(phase = Phase.WAITING, askId = event.askId, phaseStartedAt = event.now) else state
  is FlowEvent.Answered ->
    if (state.phase == Phase.WAITING) state.copy(phase = Phase.SPEAKING, answer = event.answer, phaseStartedAt = event.now) else state
  is FlowEvent.Fail ->
    if (state.phase == Phase.IDLE) state else state.copy(phase = Phase.FAILED, failure = event.reason, phaseStartedAt = event.now)
  is FlowEvent.Spoken, is FlowEvent.Dismiss -> if (state.phase == Phase.IDLE) state else IDLE
}

fun isBusy(state: FlowState): Boolean = state.phase != Phase.IDLE && state.phase != Phase.FAILED

fun shouldReturnNow(source: Source?, settings: Settings, moment: ReturnTiming): Boolean =
  source == Source.HANDSFREE && settings.returnEnabled && settings.returnApp != null && settings.returnTiming == moment
