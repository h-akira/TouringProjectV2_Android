package com.touringproject.v2.handsfree

/**
 * Where an intercom press goes. A running trial recording takes it (US-E-03.002: no question starts). While SC-19 is
 * shown, its voice input takes it, unless an ask is already under way (slice 3 spec §7.3). Otherwise a kept recording
 * playing on SC-12 is stopped first, since the microphone would pick it up, and the ask loop gets it.
 * While a trial that was left is still unwinding (up to the 3 s SCO setup), presses start nothing: the old trial may
 * still set up or release SCO, which would break the new ask's intercom route.
 */
class PressRouter(
  private val trialActive: () -> Boolean,
  private val trialBusy: () -> Boolean,
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
    if (trialBusy()) return
    if (voiceAttached() && !askBusy()) {
      voicePress()
      return
    }
    stopCheckPlayback()
    askPress()
  }

  /** The screen's "押して話す". */
  fun tap() {
    if (!trialBusy()) askTap()
  }

  /** The headset dropping SCO during a voice-recognition session is the second press. */
  fun recognitionEnded() {
    when {
      trialActive() -> stopTrial()
      voiceRecording() -> voiceStop()
      else -> askStopRecording()
    }
  }
}
