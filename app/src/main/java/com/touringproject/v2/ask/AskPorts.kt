package com.touringproject.v2.ask

import com.touringproject.v2.audio.AnswerPlayer
import com.touringproject.v2.audio.Cues
import com.touringproject.v2.audio.Recorder
import com.touringproject.v2.audio.RouteControl
import com.touringproject.v2.audio.Speech
import com.touringproject.v2.location.Positions
import com.touringproject.v2.recordings.RecordingSink
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.api.AskApi
import com.touringproject.v2.audio.MediaVolume
import com.touringproject.v2.location.Fix
import com.touringproject.v2.settings.Settings
import kotlinx.serialization.Serializable

// Everything the ask loop does to the outside world comes in through these interfaces, so the loop is testable on the JVM.

/** Fire and forget: a failure to keep an ask must not end it. */
fun interface AskLog {
  fun keep(record: AskRecord)
}

interface KeepAlive {
  /** Throws when the foreground service cannot be started. */
  fun start()
  fun stop()
}

interface NavReturn {
  suspend fun returnTo(packageName: String): Boolean
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
