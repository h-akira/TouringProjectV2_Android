package com.touringproject.v2.handsfree

import org.junit.Assert.assertEquals
import org.junit.Test

class PressRouterTest {
  private val log = mutableListOf<String>()
  private var trialActive = false
  private var trialBusy = false
  private val router = PressRouter(
    trialActive = { trialActive },
    trialBusy = { trialBusy || trialActive },
    stopTrial = { log += "trial:stop" },
    stopCheckPlayback = { log += "check:stop" },
    askPress = { log += "ask:press" },
    askStopRecording = { log += "ask:stopRecording" },
    askTap = { log += "ask:tap" },
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
  fun a_press_while_a_left_trial_still_unwinds_starts_no_question() {
    // The old trial may still set up or release SCO for up to 3 s; an ask now would share the route with it.
    trialBusy = true
    router.press()
    router.tap()
    assertEquals(emptyList<String>(), log)
  }

  @Test
  fun the_screen_button_starts_a_question_when_no_trial_runs() {
    router.tap()
    assertEquals(listOf("ask:tap"), log)
  }

  @Test
  fun the_headset_ending_recognition_sends_the_question() {
    router.recognitionEnded()
    assertEquals(listOf("ask:stopRecording"), log)
  }
}
