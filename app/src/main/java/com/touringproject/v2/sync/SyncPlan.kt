package com.touringproject.v2.sync

import com.touringproject.v2.api.HeadingSample
import com.touringproject.v2.api.NoteBody
import com.touringproject.v2.api.OfflineAskBody
import com.touringproject.v2.api.Point
import com.touringproject.v2.api.PointBody
import com.touringproject.v2.api.RideBody
import com.touringproject.v2.api.SegmentBody
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RoutePoint
import java.time.Instant

// What is left to send to the Backend, decided without touching it (slice 2 spec §5.3).

/** The most points the Backend takes in one route chunk. */
const val ROUTE_CHUNK_POINTS = 300

/** Route chunk seq holds the ride's points from index from (inclusive) to until (exclusive), in the order they were recorded. */
data class ChunkRange(val seq: Int, val from: Int, val until: Int)

/**
 * The chunks to send now, given that the ride's first sent points have gone (chunks go in order, and a failure stops
 * the rest) and it has total points. While the ride records, only full chunks go. Once it is paused or has ended, the
 * last, partial chunk goes too; when more points fill it later, it goes again under the same seq, which the Backend
 * overwrites.
 */
fun chunksToSend(sent: Int, total: Int, partialToo: Boolean): List<ChunkRange> {
  val chunks = mutableListOf<ChunkRange>()
  var seq = sent / ROUTE_CHUNK_POINTS
  while (true) {
    val from = seq * ROUTE_CHUNK_POINTS
    if (from >= total) break
    val until = minOf(from + ROUTE_CHUNK_POINTS, total)
    if (until <= sent) break
    if (until - from < ROUTE_CHUNK_POINTS && !partialToo) break
    chunks += ChunkRange(seq, from, until)
    seq += 1
  }
  return chunks
}

/** The ride's meta goes when it has changed since the Backend last took it, unless the Backend refused this very version. */
fun metaToSend(updatedAt: Long, sentUpdatedAt: Long?, rejectedUpdatedAt: Long?): Boolean =
  (sentUpdatedAt == null || sentUpdatedAt < updatedAt) && rejectedUpdatedAt != updatedAt

/** ISO 8601 in UTC with its zone ("Z"), as the Backend takes times. */
fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

/** A ride that ended because the device stopped is sent like one the rider ended (slice 2 spec §5.1). */
fun Ride.toBody() = RideBody(
  name = name,
  status = status.name.lowercase(),
  segments = segments.map { SegmentBody(iso(it.startedAt), it.endedAt?.let(::iso)) },
  autoEnded = endReason == EndReason.AUTO,
  updatedAt = iso(updatedAt),
  memo = memo,
)

fun RoutePoint.toBody() = PointBody(iso(t), lat, lon, accuracy, segment)

/** A memo opened before its ride had a point goes without a place; the sample goes only when it is whole. */
fun UnsentNote.toBody(): NoteBody {
  val location = if (latitude != null && longitude != null) Point(latitude, longitude) else null
  val sample = if (sampleLatitude != null && sampleLongitude != null && sampleAt != null) HeadingSample(sampleLatitude, sampleLongitude, iso(sampleAt)) else null
  return NoteBody(iso(at), location, sample, text, iso(updatedAt))
}

/**
 * An unreached ask goes with its place and heading; a sample (kept before slice 5) goes too while it is whole, and the
 * Backend takes the heading first (slice 5 spec §4.4).
 */
fun UnsentUnreached.toBody(): OfflineAskBody {
  val location = if (latitude != null && longitude != null) Point(latitude, longitude) else null
  val sample = if (sampleLatitude != null && sampleLongitude != null && sampleAt != null) HeadingSample(sampleLatitude, sampleLongitude, iso(sampleAt)) else null
  return OfflineAskBody(iso(askedAt), location, sample, rideId, iso(updatedAt), headingDegrees)
}
