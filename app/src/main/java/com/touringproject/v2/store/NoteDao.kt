package com.touringproject.v2.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.touringproject.v2.records.memoTime
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
  @Insert
  suspend fun insert(note: NoteEntity)

  @Query("SELECT * FROM notes WHERE id = :id")
  suspend fun note(id: String): NoteEntity?

  /** The ride's point memos in the order they were opened (SC-06). */
  @Query("SELECT * FROM notes WHERE rideId = :rideId ORDER BY at")
  fun forRide(rideId: String): Flow<List<NoteEntity>>

  @Query("SELECT EXISTS (SELECT 1 FROM rides WHERE id = :rideId)")
  suspend fun rideKept(rideId: String): Boolean

  /**
   * SC-19 from Home. False when the memo's ride is no longer kept. A memo already kept under the draft's id (saved just
   * before the process died, then saved again from the restored SC-19) takes the new text (Android #57).
   */
  @Transaction
  suspend fun create(note: NoteEntity): Boolean {
    if (!rideKept(note.rideId)) return false
    val kept = note(note.id)
    if (kept == null) {
      insert(note)
      return true
    }
    if (kept.rideId != note.rideId) return false
    return writeText(note.id, note.text, note.updatedAt)
  }

  @Query("UPDATE notes SET text = :text, updatedAt = :at WHERE id = :id")
  suspend fun setText(id: String, text: String, at: Long)

  /** SC-19 from the records. False when the memo is not kept; the same text again changes nothing. */
  @Transaction
  suspend fun writeText(id: String, text: String, now: Long): Boolean {
    val note = note(id) ?: return false
    if (note.text == text) return true
    setText(id, text, memoTime(now, note.updatedAt))
    return true
  }

  @Query("DELETE FROM notes WHERE id = :id")
  suspend fun deleteRow(id: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun markDeleted(deletion: DeletionEntity)

  /** DL-01: removes the memo and marks it for the Backend, together (slice 3 spec §4.2). False when it is not kept. */
  @Transaction
  suspend fun delete(id: String, at: Long): Boolean {
    val note = note(id) ?: return false
    deleteRow(id)
    markDeleted(DeletionEntity("note", id, note.rideId, at))
    return true
  }

  // Sending (slice 3 spec §5.1): the memos changed since they were taken, unless that version was refused.
  @Query(
    "SELECT * FROM notes WHERE (sentUpdatedAt IS NULL OR sentUpdatedAt < updatedAt) AND " +
      "(rejectedUpdatedAt IS NULL OR rejectedUpdatedAt != updatedAt) ORDER BY updatedAt",
  )
  suspend fun unsent(): List<NoteEntity>

  /**
   * The marker never moves back. The address and heading are settled by the Backend once; a send it answers without
   * them (410: deleted there) keeps what the memo has.
   */
  @Query(
    "UPDATE notes SET sentUpdatedAt = MAX(IFNULL(sentUpdatedAt, :updatedAt), :updatedAt), address = IFNULL(:address, address), " +
      "headingDegrees = IFNULL(:headingDegrees, headingDegrees) WHERE id = :id",
  )
  suspend fun markSent(id: String, updatedAt: Long, address: String?, headingDegrees: Double?)

  @Query("UPDATE notes SET rejectedUpdatedAt = :updatedAt WHERE id = :id")
  suspend fun markRefused(id: String, updatedAt: Long)
}
