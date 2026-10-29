package com.touringproject.v2.store

import com.touringproject.v2.location.Fix
import com.touringproject.v2.records.NoteDraft
import com.touringproject.v2.records.NoteEdits
import com.touringproject.v2.records.noteDraft
import com.touringproject.v2.records.toFix
import com.touringproject.v2.ride.memoFits
import kotlinx.coroutines.CancellationException

/**
 * Point memos in Room. Like RoomAskEdits, a change that was written is something new to send (changed), and a failed
 * write is logged and reported as false, so SC-19 can say so and keep what was typed (slice 3 spec §8). heading is the
 * ride's route's heading at the memo's place, as of when it was opened (slice 5 spec §4.1).
 */
class RoomNotes(
  private val notes: NoteDao,
  private val rides: RideDao,
  private val now: () -> Long,
  private val newId: () -> String,
  private val changed: () -> Unit,
  private val heading: suspend (here: Fix?) -> Double? = { null },
  private val log: (String, Throwable) -> Unit,
) : NoteEdits {
  override suspend fun draft(rideId: String): NoteDraft {
    val at = now()
    // A memo whose points cannot be read is still opened, without a place.
    val newest = try {
      rides.lastPoints(rideId, 1).firstOrNull()?.toPoint()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("note_points_failed rideId=$rideId", e)
      null
    }
    return noteDraft(newId(), rideId, at, newest, heading(newest?.toFix()))
  }

  override suspend fun create(draft: NoteDraft, text: String): Boolean =
    usable(text) && attempt("note_create_failed noteId=${draft.id}") { notes.create(draft.toEntity(text, now())) }

  override suspend fun text(noteId: String, text: String): Boolean =
    usable(text) && attempt("note_text_failed noteId=$noteId") { notes.writeText(noteId, text, now()) }

  override suspend fun delete(noteId: String): Boolean = attempt("note_delete_failed noteId=$noteId") { notes.delete(noteId, now()) }

  private fun usable(text: String) = text.isNotBlank() && memoFits(text)

  private suspend fun attempt(failure: String, write: suspend () -> Boolean): Boolean {
    val written = try {
      write()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log(failure, e)
      false
    }
    if (written) changed()
    return written
  }
}

private fun NoteDraft.toEntity(text: String, at: Long) = NoteEntity(
  id = id,
  rideId = rideId,
  at = this.at,
  latitude = latitude,
  longitude = longitude,
  sampleLatitude = null,
  sampleLongitude = null,
  sampleAt = null,
  text = text,
  updatedAt = at,
  sentUpdatedAt = null,
  rejectedUpdatedAt = null,
  address = null,
  // The device's heading until the Backend answers the one it settled (slice 5 spec §4.4).
  headingDegrees = headingDegrees,
)
