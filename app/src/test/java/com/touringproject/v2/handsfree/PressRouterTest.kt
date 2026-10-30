package com.touringproject.v2.handsfree

import org.junit.Assert.assertEquals
import org.junit.Test

class PressRouterTest {
  private val log = mutableListOf<String>()
  private var trialActive = false
  private var askBusy = false
  private var noteOpen = false
  private var voiceRecording = false
  private val router = PressRouter(
    trialActive = { trialActive },
    stopTrial = { log += "trial:stop" },
    stopCheckPlayback = { log += "check:stop" },
    askPress = { log += "ask:press" },
    askStopRecording = { log += "ask:stopRecording" },
    askTap = { log += "ask:tap" },
    askBusy = { askBusy },
    voiceAttached = { noteOpen },
    voicePress = { log += "voice:press" },
    voiceRecording = { voiceRecording },
    voiceStop = { log += "voice:stop" },
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
  fun the_screen_button_starts_a_question_when_no_trial_runs() {
    router.tap()
    assertEquals(listOf("ask:tap"), log)
  }

  @Test
  fun the_headset_ending_recognition_sends_the_question() {
    router.recognitionEnded()
    assertEquals(listOf("ask:stopRecording"), log)
  }

  // Slice 3 spec §7.3: while SC-19 is shown, the intercom button starts and stops its voice input, not an ask.
  @Test
  fun while_sc19_is_shown_a_press_goes_to_the_voice_input_and_its_end_stops_it() {
    noteOpen = true
    router.press()
    voiceRecording = true
    router.recognitionEnded()
    assertEquals(listOf("voice:press", "voice:stop"), log)
  }

  // An ask already under way keeps the button: its recording is stopped, or the rider hears that it is busy.
  @Test
  fun an_ask_under_way_keeps_the_button_even_on_sc19() {
    noteOpen = true
    askBusy = true
    router.press()
    assertEquals(listOf("check:stop", "ask:press"), log)
  }
}
