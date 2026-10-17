package com.touringproject.v2.ask

// What the rider hears when something goes wrong (slice 1 spec §8.1).
const val BUSY_MESSAGE = "いま応答中です。少し待ってからもう一度お話しください。"

fun spokenMessage(reason: FailureReason): String = when (reason) {
  FailureReason.NO_SPEECH -> "聞き取れませんでした。もう一度お話しください。"
  FailureReason.TRANSCRIBE_FAILED, FailureReason.AGENT_FAILED, FailureReason.TTS_FAILED -> "うまく答えられませんでした。もう一度お話しください。"
  FailureReason.TIMEOUT -> "時間切れです。もう一度お話しください。"
  FailureReason.NETWORK -> "通信できないため、質問できませんでした。"
  FailureReason.INVALID_KEY, FailureReason.NO_KEY -> "接続キーを確かめてください。"
  FailureReason.MIC -> "録音できませんでした。"
  FailureReason.SERVICE -> "準備できなかったため、質問できませんでした。もう一度お試しください。"
}

private val SERVER_REASONS = mapOf(
  "no_speech" to FailureReason.NO_SPEECH,
  "transcribe_failed" to FailureReason.TRANSCRIBE_FAILED,
  "agent_failed" to FailureReason.AGENT_FAILED,
  "tts_failed" to FailureReason.TTS_FAILED,
)

fun failureFromServer(error: String?): FailureReason = SERVER_REASONS[error] ?: FailureReason.AGENT_FAILED
