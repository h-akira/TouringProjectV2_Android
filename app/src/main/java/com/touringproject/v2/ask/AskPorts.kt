package com.touringproject.v2.ask

import com.touringproject.v2.api.AskApi
import com.touringproject.v2.audio.InputDevice
import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.location.Fix
import com.touringproject.v2.settings.Settings
import kotlinx.serialization.Serializable

// Everything the ask loop does to the outside world comes in through these interfaces, so the loop is testable on the JVM.

interface RouteControl {
  /** Brings up the intercom microphone; false means the phone's own microphone will be used. */
  suspend fun start(source: Source): Boolean
  suspend fun stop()
  /** The microphone of the recording that is running right now (only visible while it runs). */
  fun inputDevice(): InputDevice?
}

data class Recorded(val pcm: ByteArray, val name: String)

interface Recorder {
  suspend fun start()
  suspend fun stop(): Recorded
  suspend fun cancel()
}

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

/** Fire and forget: a failure here must not end the ask. */
interface RecordingSink {
  fun saved(meta: RecordingMeta, pcm: ByteArray)
  fun transcribed(name: String, transcript: String)
}

interface Positions {
  fun begin()
  fun end(): Pair<Fix?, List<Fix>>
}

interface KeepAlive {
  /** Throws when the foreground service cannot be started. */
  fun start()
  fun stop()
}

interface NavReturn {
  suspend fun returnTo(packageName: String): Boolean
}

interface Cues {
  fun start()
  fun sent()
  fun waiting()
  fun failure()
}

interface AnswerPlayer {
  /** Plays the clips in order; throws when a clip fails or stalls. */
  suspend fun play(urls: List<String>)
  fun stop()
}

interface Speech {
  /** Returns when the text has been said, stopped, or failed. */
  suspend fun say(text: String)
  fun stop()
}

class AskDeps(
  val now: () -> Long,
  val sleep: suspend (Long) -> Unit,
  val settings: () -> Settings,
  val apiKey: () -> String?,
  val micAllowed: () -> Boolean,
  val mediaVolume: () -> MediaVolume?,
  val api: (apiKey: String, userName: String) -> AskApi,
  val conversation: ConversationState,
  val route: RouteControl,
  val recorder: Recorder,
  val recordings: RecordingSink,
  val location: Positions,
  val service: KeepAlive,
  val nav: NavReturn,
  val cues: Cues,
  val player: AnswerPlayer,
  val speech: Speech,
  val onState: (FlowState) -> Unit,
  val log: (event: String, data: Map<String, Any?>) -> Unit,
)
