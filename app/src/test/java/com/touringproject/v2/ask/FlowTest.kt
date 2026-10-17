package com.touringproject.v2.ask

import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.ReturnTiming
import com.touringproject.v2.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTest {
  private fun run(vararg events: FlowEvent) = events.fold(IDLE) { s, e -> reduce(s, e) }

  @Test
  fun happy_path() {
    val s = run(
      FlowEvent.Start(Source.HANDSFREE, 1),
      FlowEvent.StopRecording(2),
      FlowEvent.Sent("a1", 3),
      FlowEvent.Answered("答え", 4),
    )
    assertEquals(Phase.SPEAKING, s.phase)
    assertEquals("a1", s.askId)
    assertEquals("答え", s.answer)
    assertEquals(4L, s.phaseStartedAt)
    assertEquals(IDLE, reduce(s, FlowEvent.Spoken(5)))
  }

  @Test
  fun out_of_order_events_are_ignored() {
    assertSame(IDLE, reduce(IDLE, FlowEvent.Sent("a1", 1)))
    val recording = run(FlowEvent.Start(Source.SCREEN, 1))
    assertSame(recording, reduce(recording, FlowEvent.Answered("x", 2)))
    assertSame(recording, reduce(recording, FlowEvent.Start(Source.HANDSFREE, 2)))
  }

  @Test
  fun phone_mic_flag_only_while_recording() {
    assertTrue(run(FlowEvent.Start(Source.SCREEN, 1), FlowEvent.PhoneMic(2)).phoneMic)
    assertFalse(run(FlowEvent.Start(Source.SCREEN, 1), FlowEvent.StopRecording(2), FlowEvent.PhoneMic(3)).phoneMic)
  }

  @Test
  fun failure_and_a_new_start_after_it() {
    val failed = run(FlowEvent.Start(Source.SCREEN, 1), FlowEvent.Fail(FailureReason.NETWORK, 2))
    assertEquals(Phase.FAILED, failed.phase)
    assertEquals(FailureReason.NETWORK, failed.failure)
    val again = reduce(failed, FlowEvent.Start(Source.HANDSFREE, 3))
    assertEquals(Phase.RECORDING, again.phase)
    assertEquals(null, again.failure)
    assertSame(IDLE, reduce(IDLE, FlowEvent.Fail(FailureReason.NETWORK, 1)))
  }

  @Test
  fun busy_phases() {
    assertFalse(isBusy(IDLE))
    assertTrue(isBusy(run(FlowEvent.Start(Source.SCREEN, 1))))
    assertFalse(isBusy(run(FlowEvent.Start(Source.SCREEN, 1), FlowEvent.Fail(FailureReason.MIC, 2))))
  }

  @Test
  fun return_only_for_handsfree_at_the_chosen_moment() {
    val s = Settings(userName = "abc", returnApp = LaunchableApp("maps", "マップ"))
    assertTrue(shouldReturnNow(Source.HANDSFREE, s, ReturnTiming.SENT))
    assertFalse(shouldReturnNow(Source.SCREEN, s, ReturnTiming.SENT))
    assertFalse(shouldReturnNow(Source.HANDSFREE, s, ReturnTiming.SPOKEN))
    assertFalse(shouldReturnNow(Source.HANDSFREE, s.copy(returnEnabled = false), ReturnTiming.SENT))
    assertFalse(shouldReturnNow(Source.HANDSFREE, s.copy(returnApp = null), ReturnTiming.SENT))
  }

  @Test
  fun spoken_messages() {
    assertEquals("聞き取れませんでした。もう一度お話しください。", spokenMessage(FailureReason.NO_SPEECH))
    assertEquals("うまく答えられませんでした。もう一度お話しください。", spokenMessage(FailureReason.AGENT_FAILED))
    assertEquals("時間切れです。もう一度お話しください。", spokenMessage(FailureReason.TIMEOUT))
    assertEquals("通信できないため、質問できませんでした。", spokenMessage(FailureReason.NETWORK))
    assertEquals("接続キーを確かめてください。", spokenMessage(FailureReason.NO_KEY))
    assertEquals("録音できませんでした。", spokenMessage(FailureReason.MIC))
  }

  @Test
  fun server_failure_reasons() {
    assertEquals(FailureReason.NO_SPEECH, failureFromServer("no_speech"))
    assertEquals(FailureReason.TTS_FAILED, failureFromServer("tts_failed"))
    assertEquals(FailureReason.AGENT_FAILED, failureFromServer("something_new"))
    assertEquals(FailureReason.AGENT_FAILED, failureFromServer(null))
  }

  @Test
  fun the_media_volume_read_at_the_start_stays_with_the_ask() {
    val s = run(FlowEvent.Start(Source.HANDSFREE, 1, MediaVolume(3, 15)), FlowEvent.StopRecording(2))
    assertEquals(MediaVolume(3, 15), s.mediaVolume)
  }
}
