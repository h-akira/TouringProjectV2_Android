package com.touringproject.v2.recordings

import com.touringproject.v2.audio.InputDevice
import kotlinx.serialization.Serializable

/** What SC-12 shows about each kept recording (slice 1 spec §5.4). Saved as JSON beside the PCM. */
@Serializable
data class RecordingMeta(
  val name: String,
  val recordedAt: Long,
  val durationMs: Long,
  val phoneMic: Boolean,
  val device: InputDevice?,
  val transcript: String?,
)

/** Fire and forget: a failure here must not end the ask. Each call is carried out after the ones before it. */
interface RecordingSink {
  fun saved(meta: RecordingMeta, pcm: ByteArray)
  fun transcribed(name: String, transcript: String)
  /** The recording saved goes again: its ask never reached the Backend (US-C-09.001). */
  fun discarded(name: String)
  /** The recording saved stays (its ask was taken or refused): only the newest ones are kept now (Android #80). */
  fun kept()
}
