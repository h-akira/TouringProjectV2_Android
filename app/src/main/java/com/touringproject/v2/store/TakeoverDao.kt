package com.touringproject.v2.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

data class KeptRideRow(val id: String, val updatedAt: Long, val points: Int)

data class NoteVersion(val id: String, val rideId: String, val updatedAt: Long)

data class AskVersion(val askId: String, val rideId: String?, val updatedAt: Long?, val memoUpdatedAt: Long?)

/** Reading the cloud's records in (slice 4 spec §6.3): what the merge reads of the device, and what it writes. */
@Dao
interface TakeoverDao {
  @Query("SELECT COUNT(*) FROM rides WHERE status != 'ended'")
  suspend fun activeRides(): Int

  @Query("SELECT id, updatedAt, (SELECT COUNT(*) FROM route_points WHERE route_points.rideId = rides.id) AS points FROM rides")
  suspend fun rides(): List<KeptRideRow>

  @Query("SELECT id, rideId, updatedAt FROM notes")
  suspend fun notes(): List<NoteVersion>

  @Query("SELECT askId, rideId, updatedAt, memoUpdatedAt FROM asks")
  suspend fun asks(): List<AskVersion>

  @Query("SELECT userName FROM owner WHERE id = 0")
  suspend fun owner(): String?

  @Upsert
  suspend fun setOwner(owner: OwnerEntity)

  @Query("DELETE FROM route_points")
  suspend fun clearPoints()

  @Query("DELETE FROM ride_segments")
  suspend fun clearSegments()

  @Query("DELETE FROM notes")
  suspend fun clearNotes()

  @Query("DELETE FROM asks")
  suspend fun clearAsks()

  @Query("DELETE FROM deletions")
  suspend fun clearDeletions()

  @Query("DELETE FROM rides")
  suspend fun clearRides()

  /** Another user's records: nothing of this one's stays, its deletions not yet sent included. */
  @Transaction
  suspend fun clearAll() {
    clearPoints()
    clearSegments()
    clearNotes()
    clearAsks()
    clearDeletions()
    clearRides()
  }

  @Insert
  suspend fun insertPoints(points: List<RoutePointEntity>)

  @Query("UPDATE rides SET distanceMeters = :distanceMeters WHERE id = :rideId")
  suspend fun setDistance(rideId: String, distanceMeters: Double)

  @Upsert
  suspend fun upsertNote(note: NoteEntity)
}
