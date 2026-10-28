package com.touringproject.v2.ask

import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
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

/** Fire and forget: a failure here must not end the ask. Each call is carried out after the ones before it. */
interface RecordingSink {
  fun saved(meta: RecordingMeta, pcm: ByteArray)
  fun transcribed(name: String, transcript: String)
  /** The recording saved goes again: its ask never reached the Backend (US-C-09.001). */
  fun discarded(name: String)
}

/** Fire and forget: a failure to keep an ask must not end it. */
fun interface AskLog {
  fun keep(record: AskRecord)
}

interface Positions {
  fun begin()
  /** Where the ask is: the newest fix since begin(), unless it is older than 30 s. */
  fun end(): Fix?
  /** Where a text ask is (slice 5 spec §4.1): the device's last known fix, unless it is older than 30 s. */
  suspend fun latest(): Fix?
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
  /** The start cue, durationMs long (SC-11); 0 sounds none. */
  fun start(durationMs: Int)
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

/** Where the rider's ride stands when an ask is asked for (slice 2 spec §4.7). */
sealed interface RideNow {
  data class Recording(val rideId: String) : RideNow
  data object Paused : RideNow
  data object NotRecording : RideNow
}

fun rideNow(active: Ride?): RideNow = when {
  active == null || active.status == RideStatus.ENDED -> RideNow.NotRecording
  active.status == RideStatus.PAUSED -> RideNow.Paused
  else -> RideNow.Recording(active.id)
}

/**
 * Why an ask is not taken now, or null when it is (slice 2 spec §4.7). The ask loop and Home's buttons both decide by
 * this, so the two never part (Android #48).
 */
fun refusal(ride: RideNow): FailureReason? = when (ride) {
  is RideNow.Recording -> null
  RideNow.Paused -> FailureReason.PAUSED
  RideNow.NotRecording -> FailureReason.NOT_RECORDING
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
  /** Where the asks are kept: those that reached the Backend (slice 2 spec §5.2) and those that did not (slice 4 spec §4). */
  val asks: AskLog,
  /** Whether the device has a network now: without one the ask is not sent (US-C-09.001). */
  val online: () -> Boolean,
  /** A new id for an unreached ask, which the device makes (slice 4 spec §4.2). */
  val newId: () -> String,
  /** Whether a ride is recording now: an ask starts only then (slice 2 spec §4.7). */
  val ride: () -> RideNow,
  val location: Positions,
  /**
   * The heading at here, as of the moment it is asked, from the recording ride's route (slice 5 spec §4); null when
   * there is none.
   */
  val heading: suspend (here: Fix?) -> Double?,
  val service: KeepAlive,
  val nav: NavReturn,
  val cues: Cues,
  val player: AnswerPlayer,
  val speech: Speech,
  val onState: (FlowState) -> Unit,
  val log: (event: String, data: Map<String, Any?>) -> Unit,
)
