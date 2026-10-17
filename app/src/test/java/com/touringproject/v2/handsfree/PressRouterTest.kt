package com.touringproject.v2.handsfree

import org.junit.Assert.assertEquals
import org.junit.Test

class PressRouterTest {
  private val log = mutableListOf<String>()
  private var trialActive = false
  private val router = PressRouter(
    trialActive = { trialActive },
    stopTrial = { log += "trial:stop" },
    stopCheckPlayback = { log += "check:stop" },
    askPress = { log += "ask:press" },
    askStopRecording = { log += "ask:stopRecording" },
  )

  @Test
  fun a_press_during_a_trial_ends_the_trial_and_starts_no_question() {
    // 4b #4
    trialActive = true
    router.press()
    router.recognitionEnded()
    assertEquals(listOf("trial:stop", "trial:stop"), log)
  }

  @Test
  fun a_press_stops_sc12_playback_before_the_question_starts() {
    // 4b #10: a kept recording playing would be picked up by the microphone.
    router.press()
    assertEquals(listOf("check:stop", "ask:press"), log)
  }

  @Test
  fun the_headset_ending_recognition_sends_the_question() {
    router.recognitionEnded()
    assertEquals(listOf("ask:stopRecording"), log)
  }
}
