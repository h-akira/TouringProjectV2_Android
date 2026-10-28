package com.touringproject.v2.ui

import androidx.compose.ui.graphics.Color
import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.ask.FailureReason
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.Phase
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.recordings.TRIAL_IDLE
import com.touringproject.v2.recordings.TrialPhase
import com.touringproject.v2.recordings.TrialState
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LabelsTest {
  @Test
  fun each_phase_has_its_colour() {
    assertEquals(Color(0xFFD42A2A), phaseColor(Phase.RECORDING))
    assertEquals(Color(0xFFD42A2A), phaseColor(Phase.SENDING))
    assertEquals(Color(0xFF1F4FD1), phaseColor(Phase.WAITING))
    assertEquals(Color(0xFF13804A), phaseColor(Phase.SPEAKING))
    assertEquals(Color(0xFF5B6170), phaseColor(Phase.FAILED))
  }

  @Test
  fun no_conversation() {
    assertEquals("新しい会話です", conversationLabel(null, 0))
    assertEquals("新しい会話です", conversationLabel(1_000, 1_000))
  }

  @Test
  fun shows_when_it_will_be_reset_in_japan_time() {
    val expiresAt = Instant.parse("2026-10-17T01:35:00Z").toEpochMilli()
    assertEquals("10:35 まで質問がなければ自動で区切ります", conversationLabel(expiresAt, expiresAt - 1))
  }

  @Test
  fun the_missing_key_comes_before_the_missing_return_app() {
    assertEquals(Banner.NO_KEY, homeBanner(hasKey = false, returnAppMissing = true))
    assertEquals(Banner.NO_RETURN_APP, homeBanner(hasKey = true, returnAppMissing = true))
    assertNull(homeBanner(hasKey = true, returnAppMissing = false))
  }

  @Test
  fun the_low_volume_band_comes_last() {
    assertEquals(Banner.NO_RETURN_APP, homeBanner(hasKey = true, returnAppMissing = true, volumeLow = true))
    assertEquals(Banner.LOW_VOLUME, homeBanner(hasKey = true, returnAppMissing = false, volumeLow = true))
    // A failing database is the most urgent: rides are being lost (slice 2 spec §8).
    assertEquals(Banner.STORE_FAILED, homeBanner(hasKey = false, returnAppMissing = true, volumeLow = true, storeFailed = true))
  }

  // Android #28: an ask that could not be written comes after a failing ride store, before the settings that are missing.
  @Test
  fun an_ask_that_could_not_be_written_comes_after_the_ride_store() {
    assertEquals(Banner.ASK_STORE_FAILED, homeBanner(hasKey = false, returnAppMissing = true, askStoreFailed = true))
    assertEquals(Banner.STORE_FAILED, homeBanner(hasKey = true, returnAppMissing = false, storeFailed = true, askStoreFailed = true))
  }

  @Test
  fun the_media_volume_is_low_below_a_third_of_the_maximum() {
    assertTrue(mediaVolumeLow(MediaVolume(4, 15)))
    assertFalse(mediaVolumeLow(MediaVolume(5, 15)))
    assertFalse(mediaVolumeLow(MediaVolume(0, 0)))
    assertFalse(mediaVolumeLow(null))
  }

  @Test
  fun the_volume_is_only_read_while_no_ask_holds_the_route() {
    assertTrue(volumeReadable(Phase.IDLE))
    assertTrue(volumeReadable(Phase.FAILED))
    assertFalse(volumeReadable(Phase.RECORDING))
    assertFalse(volumeReadable(Phase.SPEAKING))
  }

  @Test
  fun a_dismissed_warning_stays_closed_until_the_volume_changes() {
    assertTrue(volumeWarning(MediaVolume(3, 15), null))
    assertFalse(volumeWarning(MediaVolume(3, 15), 3))
    assertTrue(volumeWarning(MediaVolume(2, 15), 3))
    assertFalse(volumeWarning(MediaVolume(10, 15), null))
  }

  @Test
  fun kept_recordings_are_described_in_japan_time() {
    assertEquals("10/17 10:35:07", recordedAtLabel(Instant.parse("2026-10-17T01:35:07Z").toEpochMilli()))
    assertEquals("1.5秒", durationLabel(1_500))
    assertEquals(Heard("（聞き取られた文はまだありません）", false), transcriptLabel(null))
    assertEquals(Heard("聞き取れませんでした", true), transcriptLabel(""))
    assertEquals("インカム（M1-S PRO）", micLabel(false, InputDevice(InputDevice.Type.BLUETOOTH, "M1-S PRO")))
    assertEquals("本体のマイク", micLabel(true, null))
  }

  @Test
  fun the_trial_text_follows_the_trial() {
    assertEquals("試し録り", trialText(TRIAL_IDLE).button)
    assertEquals("止めて聞く", trialText(TrialState(phase = TrialPhase.RECORDING)).button)
    assertEquals("再生中: インカム（M1-S PRO）", trialText(TrialState(phase = TrialPhase.PLAYING, device = InputDevice(InputDevice.Type.BLUETOOTH, "M1-S PRO"))).status)
    assertEquals("録ったマイク: 本体のマイク", trialText(TrialState(phoneMic = true)).status)
  }

  @Test
  fun the_recording_row_says_the_limit_and_the_cue() {
    assertEquals(listOf("なし", "0.5秒", "1秒", "1.5秒", "2秒", "3秒"), listOf(0, 500, 1_000, 1_500, 2_000, 3_000).map(::cueLabel))
    assertEquals("上限 20秒 · 合図 1.5秒", recordingRowValue(Settings("rider01")))
    assertEquals("上限 60秒 · 合図 なし", recordingRowValue(Settings("rider01", recordLimitSeconds = 60, startCueMs = 0)))
  }

  @Test
  fun overlay_text_per_phase() {
    val recording = overlayText(FlowState(phase = Phase.RECORDING), seconds = 3)
    assertEquals("17", recording.big)
    assertEquals("送らずにやめる", recording.button)
    assertEquals("0", overlayText(FlowState(phase = Phase.RECORDING), seconds = 25).big)
    // SC-11: the countdown starts from the ask's own limit.
    assertEquals("42", overlayText(FlowState(phase = Phase.RECORDING, recordLimitMs = 45_000), seconds = 3).big)
    assertEquals("12", overlayText(FlowState(phase = Phase.WAITING), seconds = 12).big)
    assertEquals("愛鷹山です。", overlayText(FlowState(phase = Phase.SPEAKING, answer = "愛鷹山です。"), 0).detail)
    assertEquals("時間切れです。もう一度お話しください。", overlayText(FlowState(phase = Phase.FAILED, failure = FailureReason.TIMEOUT), 0).title)
  }

  @Test
  fun a_key_is_saved_only_after_the_backend_accepts_it() = runTest {
    val saved = mutableListOf<String>()
    assertEquals(KeyCheck.SAVED, checkAndSave("good", {}, { saved += it }))
    assertEquals(KeyCheck.INVALID, checkAndSave("bad", { throw ApiError(ApiErrorKind.INVALID_KEY) }, { saved += it }))
    assertEquals(KeyCheck.UNREACHABLE, checkAndSave("x", { throw ApiError(ApiErrorKind.NETWORK) }, { saved += it }))
    assertEquals(listOf("good"), saved)
  }

  @Test
  fun a_key_the_http_header_cannot_carry_is_refused_without_crashing() = runTest {
    val saved = mutableListOf<String>()
    assertEquals(KeyCheck.INVALID, checkAndSave("ｋｅｙ", { throw IllegalArgumentException("Unexpected char") }, { saved += it }))
    assertEquals(KeyCheck.SAVE_FAILED, checkAndSave("x", {}, { throw java.security.GeneralSecurityException("keystore") }))
    assertEquals(emptyList<String>(), saved)
  }

  @Test
  fun apps_are_filtered_by_label_or_package_name() {
    val apps = listOf(LaunchableApp("com.google.android.apps.maps", "マップ"), LaunchableApp("jp.co.yahoo.carnavi", "カーナビ"))
    assertEquals(apps, filterApps(apps, "  "))
    assertEquals(listOf(apps[0]), filterApps(apps, "MAPS"))
    assertEquals(listOf(apps[1]), filterApps(apps, "ナビ"))
  }
}
