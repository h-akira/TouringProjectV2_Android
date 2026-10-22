package com.touringproject.v2.records

import com.touringproject.v2.location.Fix
import com.touringproject.v2.location.pickHeadingSample
import com.touringproject.v2.ride.RoutePoint
import kotlinx.serialization.Serializable
import java.time.Instant

// Point memos (US-D-08, SC-19). One is written while a ride records or is paused, at the place the ride has reached,
// and belongs to that ride. Its text follows the memos' rules (Memos.kt), but it cannot be empty.

/**
 * A kept point memo as the records screens and SC-19 read it. The place is null when the ride had no point yet;
 * address is what the Backend answered (null until then, or when it could not tell); sent says the Backend has taken
 * a version of it.
 */
data class NoteRecord(
  val id: String,
  val rideId: String,
  val at: Long,
  val latitude: Double?,
  val longitude: Double?,
  val text: String,
  val address: String?,
  val sent: Boolean,
)

/**
 * A point memo opened from Home and not yet saved: when it was opened and where the ride was then, with the older
 * point the Backend works the heading out from (slice 3 spec §4.3). Nothing is written until it is saved. Serializable:
 * SC-19 keeps it in its key, so the memo stays where it was opened when the activity is recreated.
 */
@Serializable
data class NoteDraft(
  val id: String,
  val rideId: String,
  val at: Long,
  val latitude: Double?,
  val longitude: Double?,
  val sampleLatitude: Double?,
  val sampleLongitude: Double?,
  val sampleAt: Long?,
)

/** The text a point memo keeps: trimmed, and none when it is blank (SC-19 does not save an empty one). */
fun noteText(input: String): String? = memoText(input)

/** How many of the ride's last points are read for a memo's place: 30 at the route's 5 s cover the sample's minute. */
const val NOTE_RECENT_POINTS = 30

/**
 * A memo opened at at, given the ride's last points (oldest first): the newest point is its place, and its heading
 * sample is picked from the others as an ask's is (07 UC-5). Without a point it has no place.
 */
fun noteDraft(id: String, rideId: String, at: Long, recent: List<RoutePoint>): NoteDraft {
  val newest = recent.maxByOrNull { it.t } ?: return NoteDraft(id, rideId, at, null, null, null, null, null)
  val fixes = recent.map { Fix(it.lat, it.lon, it.t, it.accuracy) }
  val sample = pickHeadingSample(fixes, Fix(newest.lat, newest.lon, newest.t, newest.accuracy))
  return NoteDraft(id, rideId, at, newest.lat, newest.lon, sample?.latitude, sample?.longitude, sample?.let { Instant.parse(it.at).toEpochMilli() })
}

/** SC-19's voice input: what was heard goes at the end of what is written, on a line of its own, within 2000 characters. */
fun appendHeard(current: String, heard: String): String =
  cutMemo(if (current.isEmpty() || current.endsWith("\n")) current + heard else current + "\n" + heard)
