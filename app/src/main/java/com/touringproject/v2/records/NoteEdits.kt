package com.touringproject.v2.records

/** What SC-19 does with point memos (US-D-08). Each write returns false when nothing was written. */
interface NoteEdits {
  /** A memo opened now for the ride: where the ride is and when (slice 3 spec §4.3). Nothing is written yet. */
  suspend fun draft(rideId: String): NoteDraft
  /** Keeps the drafted memo with its first text, the cleaned text (noteText). */
  suspend fun create(draft: NoteDraft, text: String): Boolean
  /** Changes a kept memo's text. */
  suspend fun text(noteId: String, text: String): Boolean
  /** Removes the memo and marks it to be deleted on the Backend too (slice 3 spec §4.2). */
  suspend fun delete(noteId: String): Boolean
}
