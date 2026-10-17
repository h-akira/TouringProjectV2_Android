package com.touringproject.v2.ui

import androidx.compose.ui.graphics.Color
import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.ask.FailureReason
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.Phase
import com.touringproject.v2.settings.LaunchableApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
  fun overlay_text_per_phase() {
    val recording = overlayText(FlowState(phase = Phase.RECORDING), seconds = 3)
    assertEquals("17", recording.big)
    assertEquals("送らずにやめる", recording.button)
    assertEquals("0", overlayText(FlowState(phase = Phase.RECORDING), seconds = 25).big)
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
  fun apps_are_filtered_by_label_or_package_name() {
    val apps = listOf(LaunchableApp("com.google.android.apps.maps", "マップ"), LaunchableApp("jp.co.yahoo.carnavi", "カーナビ"))
    assertEquals(apps, filterApps(apps, "  "))
    assertEquals(listOf(apps[0]), filterApps(apps, "MAPS"))
    assertEquals(listOf(apps[1]), filterApps(apps, "ナビ"))
  }
}
