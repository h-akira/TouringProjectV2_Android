package com.touringproject.v2.store

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.touringproject.v2.records.SaveSummary
import kotlinx.coroutines.flow.Flow

/** What one ride has left to send, read together (Android #23). */
data class UnsentRow(val ride: RideEntity, val segments: List<SegmentEntity>, val sentPoints: Int, val totalPoints: Int)

data class RideWithSegments(
  @Embedded val ride: RideEntity,
  @Relation(parentColumn = "id", entityColumn = "rideId") val segments: List<SegmentEntity>,
)

/**
 * A ride as SC-05 lists it. pointsLeft is true while one of its points has not been taken (unsent or refused);
 * memosLeft while the memo of one of its asks has not been taken, notesLeft one of its point memos (slice 3 spec
 * §5.3), and unreachedLeft one of its unreached asks (slice 4 spec §5.4).
 */
data class RideRow(
  @Embedded val ride: RideEntity,
  @Relation(parentColumn = "id", entityColumn = "rideId") val segments: List<SegmentEntity>,
  val questions: Int,
  val pointsLeft: Boolean,
  val memosLeft: Boolean,
  val notesLeft: Boolean,
  val unreachedLeft: Boolean,
)

// What SC-14 counts (SaveSummary): what the next pass would send, but the recording ride's, and all that is kept.
private const val SUMMARY = "SELECT (SELECT COUNT(*) FROM asks WHERE $UNREACHED_UNSENT) AS unreached, " +
  "(SELECT COUNT(*) FROM rides WHERE status != 'recording' AND ($UNSENT OR $POINTS_UNSENT)) AS unsentRides, " +
  "(SELECT COUNT(*) FROM notes WHERE $UNSENT) AS notes, " +
  "(SELECT COUNT(*) FROM asks WHERE $MEMO_UNSENT) AS memos, " +
  "(SELECT COUNT(*) FROM deletions) AS deletions, (SELECT COUNT(*) FROM rides) AS rides, (SELECT COUNT(*) FROM asks) AS asks"

@Dao
interface RideDao {
  @Query("SELECT * FROM rides WHERE status != 'ended' LIMIT 1")
  suspend fun active(): RideEntity?

  @Query("SELECT * FROM rides WHERE id = :id")
  suspend fun ride(id: String): RideEntity?

  @Query("SELECT * FROM ride_segments WHERE rideId = :rideId ORDER BY seq")
  suspend fun segments(rideId: String): List<SegmentEntity>

  // Records (SC-05, SC-06). Each EXISTS reads one index of route_points, so the list stays quick while a ride records.
  @Transaction
  @Query(
    "SELECT *, (SELECT COUNT(*) FROM asks WHERE asks.rideId = rides.id) AS questions, " +
      "(EXISTS (SELECT 1 FROM route_points WHERE chunk IS NULL AND route_points.rideId = rides.id) OR " +
      "EXISTS (SELECT 1 FROM route_points WHERE refused = 1 AND route_points.rideId = rides.id)) AS pointsLeft, " +
      "EXISTS (SELECT 1 FROM asks WHERE asks.rideId = rides.id AND $MEMO_LEFT) AS memosLeft, " +
      "EXISTS (SELECT 1 FROM notes WHERE notes.rideId = rides.id AND $NOTE_LEFT) AS notesLeft, " +
      "EXISTS (SELECT 1 FROM asks WHERE asks.rideId = rides.id AND $UNREACHED_LEFT) AS unreachedLeft FROM rides",
  )
  fun rows(): Flow<List<RideRow>>

  @Transaction
  @Query("SELECT * FROM rides WHERE id = :id")
  fun watch(id: String): Flow<RideWithSegments?>

  /** SC-14 (slice 4 spec §7.1). */
  @Query(SUMMARY)
  fun summary(): Flow<SaveSummary>

  /** What is left to send now, read once: a user name is not changed while there is any (US-E-06.008). */
  @Query(SUMMARY)
  suspend fun summaryNow(): SaveSummary

  @Upsert
  suspend fun upsertRide(ride: RideEntity)

  @Query("DELETE FROM ride_segments WHERE rideId = :rideId")
  suspend fun deleteSegments(rideId: String)

  @Insert
  suspend fun insertSegments(segments: List<SegmentEntity>)

  @Insert
  suspend fun insertPoint(point: RoutePointEntity)

  @Query("SELECT * FROM route_points WHERE rideId = :rideId ORDER BY id DESC LIMIT 1")
  suspend fun lastPoint(rideId: String): RoutePointEntity?

  @Query("SELECT * FROM route_points WHERE rideId = :rideId ORDER BY id")
  suspend fun points(rideId: String): List<RoutePointEntity>

  /** The ride's last count points, newest first: where a point memo opened now is (slice 3 spec §4.3). */
  @Query("SELECT * FROM route_points WHERE rideId = :rideId ORDER BY id DESC LIMIT :count")
  suspend fun lastPoints(rideId: String, count: Int): List<RoutePointEntity>

  /** The segment's last count points, newest first: what the heading is worked out from (slice 5 spec §4.1). */
  @Query("SELECT * FROM route_points WHERE rideId = :rideId AND segment = :segment ORDER BY id DESC LIMIT :count")
  suspend fun segmentTail(rideId: String, segment: Int, count: Int): List<RoutePointEntity>

  @Query("UPDATE rides SET distanceMeters = :distanceMeters, lastAliveAt = :aliveAt WHERE id = :rideId")
  suspend fun updateDistance(rideId: String, distanceMeters: Double, aliveAt: Long)

  @Query("UPDATE rides SET lastAliveAt = :at WHERE id = :rideId")
  suspend fun markAlive(rideId: String, at: Long)

  // Sending (slice 2 spec §5.3). Points are counted and cut into chunks in the order they were recorded (id).

  @Query(
    "SELECT * FROM rides WHERE ($UNSENT) " +
      "OR id IN (SELECT rideId FROM route_points WHERE chunk IS NULL) ORDER BY updatedAt",
  )
  suspend fun unsentRides(): List<RideEntity>

  @Query("SELECT COUNT(*) FROM route_points WHERE rideId = :rideId")
  suspend fun pointCount(rideId: String): Int

  @Query("SELECT COUNT(*) FROM route_points WHERE rideId = :rideId AND chunk IS NOT NULL")
  suspend fun sentPointCount(rideId: String): Int

  @Query("SELECT * FROM route_points WHERE rideId = :rideId ORDER BY id LIMIT :count OFFSET :from")
  suspend fun pointsFrom(rideId: String, from: Int, count: Int): List<RoutePointEntity>

  /** A chunk sent again under the same seq overwrites what the Backend said of it the time before (Android #27). */
  @Query(
    "UPDATE route_points SET chunk = :seq, refused = :refused WHERE id IN " +
      "(SELECT id FROM route_points WHERE rideId = :rideId ORDER BY id LIMIT :count OFFSET :from)",
  )
  suspend fun markChunk(rideId: String, seq: Int, from: Int, count: Int, refused: Boolean)

  /** A later save of an older version (none is made today) cannot move the marker back. */
  @Query("UPDATE rides SET sentUpdatedAt = :updatedAt WHERE id = :rideId AND (sentUpdatedAt IS NULL OR sentUpdatedAt < :updatedAt)")
  suspend fun markSent(rideId: String, updatedAt: Long)

  @Query("UPDATE rides SET rejectedUpdatedAt = :updatedAt WHERE id = :rideId")
  suspend fun markRefused(rideId: String, updatedAt: Long)

  /** Keeps the sending markers: a save never undoes what the Backend has already accepted or refused. */
  @Transaction
  suspend fun saveRide(ride: RideEntity, segments: List<SegmentEntity>) {
    val saved = ride(ride.id)
    upsertRide(
      ride.copy(sentUpdatedAt = saved?.sentUpdatedAt, rejectedUpdatedAt = saved?.rejectedUpdatedAt, routeRejected = saved?.routeRejected ?: false),
    )
    deleteSegments(ride.id)
    insertSegments(segments)
  }

  @Transaction
  suspend fun addPoint(point: RoutePointEntity, distanceMeters: Double, aliveAt: Long) {
    insertPoint(point)
    updateDistance(point.rideId, distanceMeters, aliveAt)
  }

  // Deleting a ride (slice 3 spec §4.2). Each table is cleared by name rather than through the foreign keys, and the
  // asks have none.
  @Query("DELETE FROM asks WHERE rideId = :rideId")
  suspend fun deleteAsks(rideId: String)

  @Query("DELETE FROM notes WHERE rideId = :rideId")
  suspend fun deleteNotes(rideId: String)

  @Query("DELETE FROM route_points WHERE rideId = :rideId")
  suspend fun deletePoints(rideId: String)

  @Query("DELETE FROM rides WHERE id = :rideId")
  suspend fun deleteRideRow(rideId: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun markDeleted(deletion: DeletionEntity)

  @Query("SELECT * FROM deletions ORDER BY deletedAt")
  suspend fun deletions(): List<DeletionEntity>

  // Android #67: the Backend looks a place up again only for a newer version, so a point memo or an unreached ask it took
  // without one is given a new version, and goes again.
  @Query(
    "UPDATE notes SET updatedAt = MAX(:now, updatedAt + 1) WHERE address IS NULL AND latitude IS NOT NULL AND " +
      "sentUpdatedAt IS NOT NULL AND sentUpdatedAt >= updatedAt",
  )
  suspend fun retryNotePlaces(now: Long)

  @Query(
    "UPDATE asks SET updatedAt = MAX(:now, updatedAt + 1) WHERE status = 'offline' AND address IS NULL AND latitude IS NOT NULL AND " +
      "sentUpdatedAt IS NOT NULL AND sentUpdatedAt >= updatedAt",
  )
  suspend fun retryUnreachedPlaces(now: Long)

  @Transaction
  suspend fun retryPlaces(now: Long) {
    retryNotePlaces(now)
    retryUnreachedPlaces(now)
  }

  @Query("DELETE FROM deletions WHERE kind = :kind AND id = :id")
  suspend fun deletionSent(kind: String, id: String)

  /**
   * The rides with something left to send, each with its segments and point counts, in one transaction: a save that
   * lands meanwhile cannot pair an old status with new segments (Android #23).
   */
  @Transaction
  suspend fun unsentSnapshot(): List<UnsentRow> =
    unsentRides().map { UnsentRow(it, segments(it.id), sentPointCount(it.id), pointCount(it.id)) }

  /** Removes an ended ride and everything of it, and marks it for the Backend. False when it is not kept or not ended. */
  @Transaction
  suspend fun deleteEnded(rideId: String, at: Long): Boolean {
    val ride = ride(rideId) ?: return false
    if (ride.status != "ended") return false
    deleteAsks(rideId)
    deleteNotes(rideId)
    deletePoints(rideId)
    deleteSegments(rideId)
    deleteRideRow(rideId)
    markDeleted(DeletionEntity("ride", rideId, null, at))
    return true
  }
}
