package com.touringproject.v2.sync

import com.touringproject.v2.api.CloudAsk
import com.touringproject.v2.api.CloudDeleted
import com.touringproject.v2.api.CloudNote
import com.touringproject.v2.api.CloudRecord
import com.touringproject.v2.api.CloudRide
import com.touringproject.v2.api.CloudRoute
import com.touringproject.v2.ask.ASK_INPUT_VOICE
import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.ask.TIMEOUT_FAILURE
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.Segment
import com.touringproject.v2.ride.end
import java.time.OffsetDateTime

// Which of the cloud's records the device takes in (slice 4 spec §6.1, adr/0010), decided without touching either.

/** What the device keeps of a ride, as far as the merge needs it. */
data class KeptRide(val updatedAt: Long, val points: Int)

/** What the device keeps of an ask: an unreached ask's version (null for the Backend's own) and its memo's. */
data class KeptAsk(val updatedAt: Long?, val memoUpdatedAt: Long?)

/**
 * The device's records by id; deletions are its own deletions not yet sent, as kind (ask / ride / note) to id. rideOf
 * is the ride each kept ask and point memo belongs to.
 */
data class DeviceRecords(
  val rides: Map<String, KeptRide> = emptyMap(),
  val notes: Map<String, Long> = emptyMap(),
  val asks: Map<String, KeptAsk> = emptyMap(),
  val deletions: Set<Pair<String, String>> = emptySet(),
  val rideOf: Map<String, String> = emptyMap(),
)

/** A ride's meta to write. sentUpdatedAt is the version the Backend has: behind updatedAt for a ride that ends here. */
data class TakenRide(val ride: Ride, val sentUpdatedAt: Long)

/** A route point with the chunk it was sent in. */
data class TakenPoint(val chunk: Int, val point: RoutePoint)

/** A ride's whole route as the cloud has it; it replaces the device's. */
data class TakenRoute(val rideId: String, val points: List<TakenPoint>)

data class TakenNote(
  val id: String,
  val rideId: String,
  val at: Long,
  val latitude: Double?,
  val longitude: Double?,
  val text: String,
  val updatedAt: Long,
  val address: String?,
  val headingDegrees: Double?,
)

/**
 * An ask from the cloud. For an ask the device keeps, content says to write what the cloud says of the ask itself (the
 * Backend's own asks always; an unreached ask when the cloud's version is newer) and memo to write the cloud's memo
 * (when it is newer). An ask the device does not keep is written whole.
 */
data class TakenAsk(val ask: AskRecord, val memoUpdatedAt: Long?, val content: Boolean, val memo: Boolean)

/** What the device keeps no longer: the cloud has the deletion's mark. A ride goes with all that is under it. */
data class GoneRecords(val rides: Set<String> = emptySet(), val asks: Set<String> = emptySet(), val notes: Set<String> = emptySet())

/**
 * What to write. count is how many records the device takes in or loses (SC-14 tells it); a Backend's ask written again
 * as the device had it is not counted, nor a route apart from its ride.
 */
data class Merge(
  val rides: List<TakenRide>,
  val routes: List<TakenRoute>,
  val notes: List<TakenNote>,
  val asks: List<TakenAsk>,
  val gone: GoneRecords,
  val count: Int,
)

/** The Backend's times are ISO 8601 with their zone; one that cannot be read throws, and the read fails as a whole. */
fun epochMs(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

/**
 * The newer version wins: the cloud's when it is newer, the device's otherwise (it goes on the next pass). A deletion's
 * mark in the cloud removes the device's record; one of the device's own deletions keeps the cloud's copy out. What only
 * the device has stays. Rides compare their meta, point memos and unreached asks their updatedAt, and memos their
 * memoUpdatedAt; a route only grows, so the longer one wins. Nothing comes in without its ride.
 */
fun merge(device: DeviceRecords, cloud: List<CloudRecord>, now: Long): Merge {
  val marks = cloud.filterIsInstance<CloudDeleted>()
  fun marked(target: String) = marks.filter { it.target == target }.map { it.id }.toSet()
  val goneRides = marked("ride")
  val goneAsks = marked("ask")
  val goneNotes = marked("note")
  fun heldBack(kind: String, id: String, rideId: String?) =
    (kind to id) in device.deletions || (rideId != null && (rideId in goneRides || ("ride" to rideId) in device.deletions))

  val chunks = cloud.filterIsInstance<CloudRoute>().groupBy { it.rideId }
  val cloudRides = cloud.filterIsInstance<CloudRide>().filter { !heldBack("ride", it.rideId, it.rideId) }
  val rides = mutableListOf<TakenRide>()
  val routes = mutableListOf<TakenRoute>()
  for (c in cloudRides) {
    val updatedAt = epochMs(c.updatedAt)
    val mine = device.rides[c.rideId]
    val points = chunks[c.rideId].orEmpty().sortedBy { it.seq }.flatMap { chunk ->
      chunk.points.map { TakenPoint(chunk.seq, RoutePoint(it.segment, epochMs(it.t), it.lat, it.lon, it.accuracy)) }
    }
    if (mine == null || mine.updatedAt < updatedAt) rides += TakenRide(c.toRide(updatedAt, points, now), updatedAt)
    if (points.size > (mine?.points ?: 0)) routes += TakenRoute(c.rideId, points)
  }
  val present = (device.rides.keys - goneRides) + cloudRides.map { it.rideId }

  val notes = cloud.filterIsInstance<CloudNote>()
    .filter { !heldBack("note", it.noteId, it.rideId) && it.noteId !in goneNotes && it.rideId in present }
    .mapNotNull { c ->
      val updatedAt = epochMs(c.updatedAt)
      val mine = device.notes[c.noteId]
      if (mine != null && mine >= updatedAt) return@mapNotNull null
      TakenNote(c.noteId, c.rideId, epochMs(c.at), c.location?.latitude, c.location?.longitude, c.text, updatedAt, c.address, c.headingDegrees)
    }

  var counted = 0
  val asks = cloud.filterIsInstance<CloudAsk>().mapNotNull { c ->
    val rideId = c.rideId ?: return@mapNotNull null
    if (heldBack("ask", c.askId, rideId) || c.askId in goneAsks || rideId !in present) return@mapNotNull null
    val ask = c.toRecord() ?: return@mapNotNull null
    val memoUpdatedAt = c.memoUpdatedAt?.let(::epochMs)
    val mine = device.asks[c.askId]
    val unreached = ask.outcome == AskOutcome.OFFLINE
    val content = mine == null || !unreached || (mine.updatedAt ?: Long.MIN_VALUE) < (ask.updatedAt ?: Long.MIN_VALUE)
    val memo = memoUpdatedAt != null && (mine?.memoUpdatedAt ?: Long.MIN_VALUE) < memoUpdatedAt
    if (mine == null || memo || (unreached && content)) counted += 1
    if (mine != null && !content && !memo) return@mapNotNull null
    TakenAsk(ask, memoUpdatedAt, content, memo)
  }

  val gone = GoneRecords(goneRides intersect device.rides.keys, goneAsks intersect device.asks.keys, goneNotes intersect device.notes.keys)
  // An ask or point memo that goes with its ride is counted as the ride (Android #77).
  val apart = (gone.asks + gone.notes).count { device.rideOf[it] !in gone.rides }
  val count = rides.size + notes.size + counted + gone.rides.size + apart
  return Merge(rides, routes, notes, asks, gone, count)
}

/**
 * A ride left recording or paused in the cloud (one recorded before the app was put in again) ends here at its last
 * point, or at the start of its last segment without one, as one the device stopped: no service records it now.
 */
private fun CloudRide.toRide(updatedAt: Long, points: List<TakenPoint>, now: Long): Ride {
  val status = RideStatus.valueOf(status.uppercase())
  val kept = Ride(
    id = rideId,
    name = name,
    status = status,
    segments = segments.mapIndexed { i, s -> Segment(i, epochMs(s.startedAt), s.endedAt?.let(::epochMs), null) },
    endReason = if (status != RideStatus.ENDED) null else if (autoEnded) EndReason.AUTO else EndReason.MANUAL,
    deadline = null,
    lastAliveAt = null,
    distanceMeters = 0.0,
    updatedAt = updatedAt,
    memo = memo,
  )
  if (status == RideStatus.ENDED) return kept
  val last = maxOf(points.maxOfOrNull { it.point.t } ?: Long.MIN_VALUE, kept.segments.last().startedAt)
  return kept.end(last, EndReason.SHUTDOWN, now)
}

/**
 * The device's copy of a cloud ask. An answer the rider could hear counts as answered, as the ask loop keeps it; one
 * still waiting for its result (processing) has none now and comes in as a failure without one, a time-out. null when
 * it has no time to place it at.
 */
fun CloudAsk.toRecord(): AskRecord? {
  val askedAt = askedAt?.let(::epochMs) ?: return null
  val unreached = status == "offline"
  val heard = status == "completed" || (error == "tts_failed" && !answer.isNullOrEmpty())
  val outcome = when {
    unreached -> AskOutcome.OFFLINE
    heard -> AskOutcome.ANSWERED
    else -> AskOutcome.FAILED
  }
  return AskRecord(
    askId = askId,
    askedAt = askedAt,
    answeredAt = answeredAt?.let(::epochMs),
    transcript = question ?: transcript,
    answer = answer,
    latitude = location?.latitude,
    longitude = location?.longitude,
    address = address,
    headingDegrees = headingDegrees,
    input = if (inputType == "text") "text" else ASK_INPUT_VOICE,
    conversationId = conversationId,
    newConversation = newConversation,
    outcome = outcome,
    failure = when {
      outcome != AskOutcome.FAILED -> null
      status == "processing" -> TIMEOUT_FAILURE
      else -> error ?: "agent_failed"
    },
    rideId = rideId,
    memo = memo,
    updatedAt = if (unreached) updatedAt?.let(::epochMs) ?: askedAt else null,
    sent = true,
  )
}
