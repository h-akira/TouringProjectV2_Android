package com.touringproject.v2.ride

import com.touringproject.v2.location.Fix
import com.touringproject.v2.location.distanceMeters

// A ride is a list of recorded segments; pausing closes one and resuming opens the next (slice 2 spec §4.1).

enum class RideStatus { RECORDING, PAUSED, ENDED }

/** SHUTDOWN is kept in the record but shown and sent like MANUAL (slice 2 spec §4.5). */
enum class EndReason { MANUAL, AUTO, SHUTDOWN }

const val HOUR_MS = 3_600_000L

/** US-C-08.003. SC-16 (slice 2 plan 3) lets the rider choose another one. */
const val DEFAULT_MAX_RECORDING_MS = 24 * HOUR_MS

/** SC-17. */
const val RIDE_NAME_MAX = 40

/** bootCount is the device's boot count when the segment started (null when the system does not tell). */
data class Segment(val seq: Int, val startedAt: Long, val endedAt: Long?, val bootCount: Int?)

/**
 * deadline is when the open segment reaches the longest recording time (null unless recording).
 * lastAliveAt is the last time the recording service was known to run. distanceMeters is cached so that
 * the home card and the lists need not read every point.
 */
data class Ride(
  val id: String,
  val name: String,
  val status: RideStatus,
  val segments: List<Segment>,
  val endReason: EndReason?,
  val deadline: Long?,
  val lastAliveAt: Long?,
  val distanceMeters: Double,
  val updatedAt: Long,
)

/** One stored route point. segment is the seq of the segment it was recorded in. */
data class RoutePoint(val segment: Int, val t: Long, val lat: Double, val lon: Double, val accuracy: Double?)

/** Returns the trimmed name, or null when it cannot name a ride (SC-17, SC-04: required, up to 40 characters). */
fun rideName(input: String): String? = input.trim().takeIf { it.isNotEmpty() && it.length <= RIDE_NAME_MAX }

fun newRide(id: String, name: String, now: Long, bootCount: Int?, maxRecordingMs: Long) = Ride(
  id = id,
  name = name,
  status = RideStatus.RECORDING,
  segments = listOf(Segment(0, now, null, bootCount)),
  endReason = null,
  deadline = now + maxRecordingMs,
  lastAliveAt = now,
  distanceMeters = 0.0,
  updatedAt = now,
)

private fun Ride.closeOpenSegment(at: Long) = segments.map {
  if (it.endedAt == null) it.copy(endedAt = maxOf(at, it.startedAt)) else it
}

fun Ride.pause(now: Long): Ride {
  check(status == RideStatus.RECORDING) { "only a recording ride can pause" }
  return copy(status = RideStatus.PAUSED, segments = closeOpenSegment(now), deadline = null, lastAliveAt = now, updatedAt = now)
}

fun Ride.resume(now: Long, bootCount: Int?, maxRecordingMs: Long): Ride {
  check(status == RideStatus.PAUSED) { "only a paused ride can resume" }
  val next = Segment(segments.last().seq + 1, now, null, bootCount)
  return copy(status = RideStatus.RECORDING, segments = segments + next, deadline = now + maxRecordingMs, lastAliveAt = now, updatedAt = now)
}

/** Ends the ride as of at (the open segment closes there); now is when the change is made. */
fun Ride.end(at: Long, reason: EndReason, now: Long, name: String = this.name): Ride {
  check(status != RideStatus.ENDED) { "the ride has already ended" }
  return copy(name = name, status = RideStatus.ENDED, segments = closeOpenSegment(at), endReason = reason, deadline = null, updatedAt = now)
}

/** The time spent recording; an open segment counts up to now (US-C-07.006). */
fun Ride.recordedMs(now: Long): Long = segments.sumOf { (it.endedAt ?: maxOf(now, it.startedAt)) - it.startedAt }

data class Pauses(val count: Int, val totalMs: Long)

/** The gaps between segments (US-C-07.007). A paused ride's current pause is not counted until it resumes. */
fun Ride.pauses(): Pauses {
  val gaps = segments.zipWithNext { a, b -> b.startedAt - (a.endedAt ?: b.startedAt) }
  return Pauses(gaps.size, gaps.sum())
}

fun Ride.startedAt(): Long = segments.first().startedAt

/** The end of the last closed segment, or null while a segment is open. */
fun Ride.endedAt(): Long? = segments.last().endedAt

/** How far a new point adds to the ride: points of different segments are never joined (US-C-07.006). */
fun stepMeters(previous: RoutePoint?, next: RoutePoint): Double {
  if (previous == null || previous.segment != next.segment) return 0.0
  return distanceMeters(Fix(previous.lat, previous.lon, previous.t, null), Fix(next.lat, next.lon, next.t, null))
}

/** What to do with a ride left recording or paused when the app starts again (slice 2 spec §4.5). */
sealed interface Reconcile {
  data object Nothing : Reconcile
  data class EndShutdown(val at: Long) : Reconcile
  data class EndAuto(val at: Long) : Reconcile
  data object Resume : Reconcile
  data object KeepPaused : Reconcile
}

fun reconcile(ride: Ride?, now: Long, bootCount: Int?): Reconcile {
  if (ride == null || ride.status == RideStatus.ENDED) return Reconcile.Nothing
  val last = ride.segments.last()
  if (bootCount != null && last.bootCount != null && bootCount > last.bootCount) {
    val closed = last.endedAt ?: last.startedAt
    return Reconcile.EndShutdown(maxOf(ride.lastAliveAt ?: closed, closed))
  }
  if (ride.status == RideStatus.PAUSED) return Reconcile.KeepPaused
  val deadline = ride.deadline
  if (deadline != null && now >= deadline) return Reconcile.EndAuto(deadline)
  return Reconcile.Resume
}
