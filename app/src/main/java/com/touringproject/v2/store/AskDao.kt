package com.touringproject.v2.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.touringproject.v2.records.memoTime
import kotlinx.coroutines.flow.Flow

@Dao
interface AskDao {
  @Upsert
  suspend fun upsert(ask: AskEntity)

  @Query("SELECT EXISTS (SELECT 1 FROM rides WHERE id = :rideId)")
  suspend fun rideKept(rideId: String): Boolean

  /**
   * Writes an ask at the end of its loop, unless its ride is no longer kept: it was deleted while the ask waited for the
   * answer (Android #54). False when it was not written.
   */
  @Transaction
  suspend fun keep(ask: AskEntity): Boolean {
    if (ask.rideId != null && !rideKept(ask.rideId)) return false
    upsert(ask)
    return true
  }

  @Query("SELECT * FROM asks WHERE askId = :askId")
  suspend fun ask(askId: String): AskEntity?

  /** How many of the ride's asks are kept, answered or failed; follows every new one (SC-02, SC-04). */
  @Query("SELECT COUNT(*) FROM asks WHERE rideId = :rideId")
  fun countForRide(rideId: String): Flow<Int>

  /** The ride's asks in the order they were asked (SC-06, SC-07). */
  @Query("SELECT * FROM asks WHERE rideId = :rideId ORDER BY askedAt")
  fun forRide(rideId: String): Flow<List<AskEntity>>

  @Query("UPDATE asks SET memo = :memo, memoUpdatedAt = :at WHERE askId = :askId")
  suspend fun setMemo(askId: String, memo: String?, at: Long)

  /** SC-08. False when the ask is not kept; the same memo again changes nothing. */
  @Transaction
  suspend fun writeMemo(askId: String, memo: String?, now: Long): Boolean {
    val ask = ask(askId) ?: return false
    if (ask.memo == memo) return true
    setMemo(askId, memo, memoTime(now, ask.memoUpdatedAt))
    return true
  }

  @Query("DELETE FROM asks WHERE askId = :askId")
  suspend fun deleteRow(askId: String)

  // Sending memos (slice 3 spec §5.1): the asks whose memo changed since it was taken, unless that version was refused.
  @Query("SELECT askId, memo, memoUpdatedAt FROM asks WHERE $MEMO_UNSENT ORDER BY memoUpdatedAt")
  suspend fun unsentMemos(): List<AskMemoRow>

  @Query(
    "UPDATE asks SET memoSentUpdatedAt = :updatedAt WHERE askId = :askId AND (memoSentUpdatedAt IS NULL OR memoSentUpdatedAt < :updatedAt)",
  )
  suspend fun markMemoSent(askId: String, updatedAt: Long)

  @Query("UPDATE asks SET memoRejectedUpdatedAt = :updatedAt WHERE askId = :askId")
  suspend fun markMemoRefused(askId: String, updatedAt: Long)

  // Sending unreached asks (slice 4 spec §5.1): those changed since they were taken, unless that version was refused.
  @Query("SELECT * FROM asks WHERE $UNREACHED_UNSENT ORDER BY updatedAt")
  suspend fun unsentUnreached(): List<AskEntity>

  /** As a point memo's: the marker never moves back, and an answer without an address or heading keeps what it has. */
  @Query(
    "UPDATE asks SET sentUpdatedAt = MAX(IFNULL(sentUpdatedAt, :updatedAt), :updatedAt), address = IFNULL(:address, address), " +
      "headingDegrees = IFNULL(:headingDegrees, headingDegrees) WHERE askId = :askId",
  )
  suspend fun markUnreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?)

  @Query("UPDATE asks SET rejectedUpdatedAt = :updatedAt WHERE askId = :askId")
  suspend fun markUnreachedRefused(askId: String, updatedAt: Long)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun markDeleted(deletion: DeletionEntity)

  /** DL-01: removes the ask and marks it for the Backend, together (slice 3 spec §4.2). False when it is not kept. */
  @Transaction
  suspend fun delete(askId: String, at: Long): Boolean {
    val ask = ask(askId) ?: return false
    deleteRow(askId)
    markDeleted(DeletionEntity("ask", askId, ask.rideId, at))
    return true
  }
}

data class AskMemoRow(val askId: String, val memo: String?, val memoUpdatedAt: Long)
