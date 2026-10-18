package com.touringproject.v2.ui

import androidx.compose.ui.graphics.Color
import com.touringproject.v2.ask.Phase
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.recordings.TrialPhase
import com.touringproject.v2.recordings.TrialState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

/** leads is true when tapping the band opens the setting that fixes it. */
enum class Banner(val text: String, val leads: Boolean = true) {
  STORE_FAILED("記録を保存できません", leads = false),
  NO_KEY("接続キーが未設定です（押して設定）"),
  NO_RETURN_APP("戻り先のアプリが未設定です（押して選ぶ）"),
  LOW_VOLUME("メディアの音量が低く、回答が聞こえないかもしれません", leads = false),
}

/** SC-02 shows at most one warning band, the most urgent first. */
fun homeBanner(hasKey: Boolean, returnAppMissing: Boolean, volumeLow: Boolean = false, storeFailed: Boolean = false): Banner? = when {
  storeFailed -> Banner.STORE_FAILED
  !hasKey -> Banner.NO_KEY
  returnAppMissing -> Banner.NO_RETURN_APP
  volumeLow -> Banner.LOW_VOLUME
  else -> null
}

/** Answers and cues play on the media stream; below a third of the maximum they get lost on the intercom. */
fun mediaVolumeLow(volume: MediaVolume?): Boolean = volume != null && volume.max > 0 && volume.current * 3 < volume.max

/** While an ask holds the intercom route, the music stream reports the call device's volume, not the media one. */
fun volumeReadable(phase: Phase): Boolean = phase == Phase.IDLE || phase == Phase.FAILED

/** A null reading (media still on the intercom's call link, which lingers after an ask) keeps the last good one. */
fun nextVolume(previous: MediaVolume?, reading: MediaVolume?): MediaVolume? = reading ?: previous

/** Low, and not dismissed at this very level: a dismissal lasts until the volume changes. */
fun volumeWarning(volume: MediaVolume?, dismissedAt: Int?): Boolean = mediaVolumeLow(volume) && volume?.current != dismissedAt

// SC-12: how a kept recording is described, in Japan time.
private val RECORDED_AT = DateTimeFormatter.ofPattern("M/d HH:mm:ss").withZone(ZoneId.of("Asia/Tokyo"))

fun recordedAtLabel(ms: Long): String = RECORDED_AT.format(Instant.ofEpochMilli(ms))

fun durationLabel(ms: Long): String = String.format(Locale.ROOT, "%.1f秒", ms / 1000.0)

data class Heard(val text: String, val failed: Boolean)

fun transcriptLabel(transcript: String?): Heard = when {
  transcript == null -> Heard("（聞き取られた文はまだありません）", false)
  transcript.isEmpty() -> Heard("聞き取れませんでした", true)
  else -> Heard(transcript, false)
}

/** One line about the microphone: the mode, and the device the system reported. */
fun micLabel(phoneMic: Boolean, device: InputDevice?): String {
  val kind = if (phoneMic) "本体のマイク" else "インカム"
  return if (device != null) "$kind（${device.name}）" else kind
}

data class TrialText(val button: String, val status: String)

fun trialText(state: TrialState): TrialText = when {
  state.phase == TrialPhase.RECORDING -> TrialText("止めて聞く", "録音中です。話してください（インカムのボタンでも止められます）")
  state.phase == TrialPhase.PLAYING -> TrialText("再生を止める", "再生中: ${micLabel(state.phoneMic, state.device)}")
  state.device != null || state.phoneMic -> TrialText("試し録り", "録ったマイク: ${micLabel(state.phoneMic, state.device)}")
  else -> TrialText("試し録り", "質問と同じマイクで録って、すぐに再生します。送りません。")
}
