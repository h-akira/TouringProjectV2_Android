package com.touringproject.v2.handsfree

/**
 * Where an intercom press goes. A running trial recording takes it (US-E-03.002: no question starts). While SC-19 is
 * in front, its voice input takes it, unless an ask is already under way (slice 3 spec §7.3). Otherwise a kept recording
 * playing on SC-12 is stopped first, since the microphone would pick it up, and the ask loop gets it. A trial or a
 * voice input that was left and still unwinds holds the audio route; the ask waits for its lease to be let go
 * ([com.touringproject.v2.audio.RouteLeases]), so the router has nothing to hold back.
 */
class PressRouter(
  private val trialActive: () -> Boolean,
  private val stopTrial: () -> Unit,
  private val stopCheckPlayback: () -> Unit,
  private val askPress: () -> Unit,
  private val askStopRecording: () -> Unit,
  private val askTap: () -> Unit,
  private val askBusy: () -> Boolean = { false },
  private val voiceAttached: () -> Boolean = { false },
  private val voicePress: () -> Unit = {},
  private val voiceRecording: () -> Boolean = { false },
  private val voiceStop: () -> Unit = {},
) {
  fun press() {
    if (trialActive()) {
      stopTrial()
      return
    }
    if (voiceAttached() && !askBusy()) {
      voicePress()
      return
    }
    stopCheckPlayback()
    askPress()
  }

  /** The screen's "押して話す". */
  fun tap() = askTap()

  /** The headset dropping SCO during a voice-recognition session is the second press. */
  fun recognitionEnded() {
    when {
      trialActive() -> stopTrial()
      voiceRecording() -> voiceStop()
      else -> askStopRecording()
    }
  }
}
