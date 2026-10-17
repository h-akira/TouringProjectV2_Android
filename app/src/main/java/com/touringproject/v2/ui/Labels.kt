package com.touringproject.v2.ui

import androidx.compose.ui.graphics.Color
import com.touringproject.v2.ask.Phase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// SC-03: the state must be readable from color alone.
fun phaseColor(phase: Phase): Color = when (phase) {
  Phase.IDLE -> Color(0xFF000000)
  Phase.RECORDING, Phase.SENDING -> Color(0xFFD42A2A)
  Phase.WAITING -> Color(0xFF1F4FD1)
  Phase.SPEAKING -> Color(0xFF13804A)
  Phase.FAILED -> Color(0xFF5B6170)
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Tokyo"))

// SC-02: when the current conversation lapses, shown in Japan time.
fun conversationLabel(expiresAt: Long?, now: Long): String =
  if (expiresAt == null || expiresAt <= now) "新しい会話です"
  else "${HHMM.format(Instant.ofEpochMilli(expiresAt))} まで質問がなければ自動で区切ります"

enum class Banner(val text: String) {
  NO_KEY("接続キーが未設定です（押して設定）"),
  NO_RETURN_APP("戻り先のアプリが未設定です（押して選ぶ）"),
}

/** SC-02 shows at most one warning band, the most urgent first. */
fun homeBanner(hasKey: Boolean, returnAppMissing: Boolean): Banner? = when {
  !hasKey -> Banner.NO_KEY
  returnAppMissing -> Banner.NO_RETURN_APP
  else -> null
}
