package com.touringproject.v2.handsfree

/**
 * Where an intercom press goes. A running trial recording takes it (US-E-03.002: no question starts); otherwise a
 * kept recording playing on SC-12 is stopped first, since the microphone would pick it up, and the ask loop gets it.
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
) {
  fun press() {
    if (trialActive()) {
      stopTrial()
      return
    }
    if (trialBusy()) return
    stopCheckPlayback()
    askPress()
  }

  /** The screen's "押して話す". */
  fun tap() {
    if (!trialBusy()) askTap()
  }

  /** The headset dropping SCO during a voice-recognition session is the second press. */
  fun recognitionEnded() {
    if (trialActive()) stopTrial() else askStopRecording()
  }
}
