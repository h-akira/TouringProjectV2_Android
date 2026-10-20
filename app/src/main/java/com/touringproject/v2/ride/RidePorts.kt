package com.touringproject.v2.ride

// Everything the ride controller does to the outside world comes in through these interfaces, so it is testable on the JVM.

interface RideStore {
  /** The ride that is recording or paused, if any (only one can be: US-C-01.005). */
  suspend fun active(): Ride?
  /** Any kept ride, ended or not. */
  suspend fun ride(id: String): Ride?
  /** Writes the ride and its segments. The sent marker of an earlier write is kept. */
  suspend fun save(ride: Ride)
  suspend fun lastPoint(rideId: String): RoutePoint?
  /** Adds a point and, in the same transaction, the ride's new distance and the time it was seen alive. */
  suspend fun addPoint(rideId: String, point: RoutePoint, distanceMeters: Double, aliveAt: Long)
  suspend fun markAlive(rideId: String, at: Long)
}

/** Wakes the app at a ride's deadline even when the process has died (slice 2 spec §4.4). */
interface DeadlineAlarm {
  fun set(rideId: String, at: Long)
  fun cancel()
}

class RideDeps(
  val now: () -> Long,
  /** The device's boot count, or null when the system does not tell. */
  val bootCount: () -> Int?,
  val newId: () -> String,
  val maxRecordingMs: () -> Long,
  val store: RideStore,
  val alarm: DeadlineAlarm,
  /** Tells the foreground service whether a ride is recording (slice 2 spec §4.2). */
  val recording: (Boolean) -> Unit,
  val autoEnded: (Ride) -> Unit,
  /** A change of the ride was saved: something new to send (slice 2 spec §5.3). */
  val changed: () -> Unit,
  /** A route point was saved. */
  val pointAdded: () -> Unit,
  val log: (event: String, data: Map<String, Any?>) -> Unit,
)
