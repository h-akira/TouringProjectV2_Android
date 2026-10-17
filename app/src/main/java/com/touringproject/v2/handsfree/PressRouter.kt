package com.touringproject.v2.handsfree

/**
 * Where an intercom press goes. A running trial recording takes it (US-E-03.002: no question starts); otherwise a
 * kept recording playing on SC-12 is stopped first, since the microphone would pick it up, and the ask loop gets it.
 */
class PressRouter(
  private val trialActive: () -> Boolean,
  private val stopTrial: () -> Unit,
  private val stopCheckPlayback: () -> Unit,
  private val askPress: () -> Unit,
  private val askStopRecording: () -> Unit,
) {
  fun press() {
    if (trialActive()) {
      stopTrial()
      return
    }
    stopCheckPlayback()
    askPress()
  }

  /** The headset dropping SCO during a voice-recognition session is the second press. */
  fun recognitionEnded() {
    if (trialActive()) stopTrial() else askStopRecording()
  }
}
