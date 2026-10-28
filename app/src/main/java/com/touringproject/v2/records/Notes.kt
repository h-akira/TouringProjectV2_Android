package com.touringproject.v2.records

import com.touringproject.v2.location.Fix
import com.touringproject.v2.ride.RoutePoint
import kotlinx.serialization.Serializable

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
 * A point memo opened from Home and not yet saved: when it was opened, where the ride was then, and the heading the
 * device worked out there (slice 3 spec §4.3, slice 5 spec §4). Nothing is written until it is saved. Serializable:
 * SC-19 keeps it in its key, so the memo stays where it was opened when the activity is recreated.
 */
@Serializable
data class NoteDraft(
  val id: String,
  val rideId: String,
  val at: Long,
  val latitude: Double?,
  val longitude: Double?,
  val headingDegrees: Double? = null,
)

/** The text a point memo keeps: trimmed, and none when it is blank (SC-19 does not save an empty one). */
fun noteText(input: String): String? = memoText(input)

/** The ride's newest point as the place a memo is written at (slice 3 spec §4.3). */
fun RoutePoint.toFix() = Fix(lat, lon, t, accuracy)

/** A memo opened at at: the ride's newest point is its place. Without a point it has no place. */
fun noteDraft(id: String, rideId: String, at: Long, newest: RoutePoint?, headingDegrees: Double?) =
  NoteDraft(id, rideId, at, newest?.lat, newest?.lon, headingDegrees)

/** The text with what was heard added; whole is false when some of it did not fit (Android #61). */
data class Appended(val text: String, val whole: Boolean)

/** SC-19's voice input: what was heard goes at the end of what is written, on a line of its own, within 2000 characters. */
fun appendHeard(current: String, heard: String): Appended {
  val joined = if (current.isEmpty() || current.endsWith("\n")) current + heard else current + "\n" + heard
  val kept = cutMemo(joined)
  return Appended(kept, kept.length == joined.length)
}
