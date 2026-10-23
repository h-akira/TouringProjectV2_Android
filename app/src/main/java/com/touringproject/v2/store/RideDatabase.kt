package com.touringproject.v2.store

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.touringproject.v2.records.SaveSummary
import com.touringproject.v2.records.memoTime
import kotlinx.coroutines.flow.Flow

// The device's record of rides (slice 2 spec §5.1). Every schema version is exported to app/schemas and is moved
// forward by a Migration; the database is never rebuilt destructively.

@Entity(tableName = "rides")
data class RideEntity(
  @PrimaryKey val id: String,
  val name: String,
  /** recording / paused / ended */
  val status: String,
  /** manual / auto / shutdown; null until the ride ends */
  val endReason: String?,
  val deadline: Long?,
  val lastAliveAt: Long?,
  val distanceMeters: Double,
  val updatedAt: Long,
  /** The updatedAt the Backend last accepted (slice 2 plan 3); null until then. */
  val sentUpdatedAt: Long?,
  /** The updatedAt the Backend refused with 400; that version is not sent again (slice 2 spec §5.3). */
  val rejectedUpdatedAt: Long? = null,
  /**
   * Version 2's mark of a refused route chunk. No longer written: a refusal is kept on the chunk's points
   * ([RoutePointEntity.refused], Android #27). The column stays, because dropping it would rebuild the table its
   * segments and points refer to.
   */
  @ColumnInfo(defaultValue = "0") val routeRejected: Boolean = false,
  /** The ride's memo (US-D-04.002); null when there is none. Part of the meta that is sent (slice 3 spec §4.1). */
  val memo: String? = null,
)

@Entity(
  tableName = "ride_segments",
  primaryKeys = ["rideId", "seq"],
  foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
)
data class SegmentEntity(val rideId: String, val seq: Int, val startedAt: Long, val endedAt: Long?, val bootCount: Int?)

@Entity(
  tableName = "route_points",
  // chunk first: finding the rides with points left to send reads only the unsent ones (slice 2 plan 3a).
  indices = [Index(value = ["rideId", "segment", "t"]), Index(value = ["chunk", "rideId"]), Index(value = ["refused", "rideId"])],
  foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
)
data class RoutePointEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val rideId: String,
  val segment: Int,
  val t: Long,
  val lat: Double,
  val lon: Double,
  val accuracy: Double?,
  /** The seq of the chunk it was sent in (slice 2 plan 3); null until then. */
  val chunk: Int?,
  /** True while the Backend has refused that chunk with 400; it is not sent again unless the chunk grows (Android #27). */
  @ColumnInfo(defaultValue = "0") val refused: Boolean = false,
)

/**
 * An ask whose loop ended with an answer or a failure the Backend reported (slice 2 spec §5.2), or one that never
 * reached the Backend (slice 4 spec §4.2: status offline). The device makes the latter's id and sends it itself.
 */
@Entity(tableName = "asks", indices = [Index(value = ["rideId"]), Index(value = ["askedAt"])])
data class AskEntity(
  @PrimaryKey val askId: String,
  val askedAt: Long,
  /** When the answer arrived; null for a failed ask. */
  val answeredAt: Long?,
  val transcript: String?,
  val answer: String?,
  val latitude: Double?,
  val longitude: Double?,
  val address: String?,
  val headingDegrees: Double?,
  /** voice / text */
  val input: String,
  /** null for an unreached ask: it never joined a conversation (US-C-09.006). */
  val conversationId: String?,
  /** True for the first ask of a conversation: the conversation breaks just before it (US-C-03.004). */
  val newConversation: Boolean,
  /** answered / failed / offline */
  val status: String,
  /** no_speech / transcribe_failed / agent_failed / tts_failed / timeout; null when answered */
  val failure: String?,
  /**
   * The ride that was recording when the ask started. Asks need a recording ride since version 4 (slice 2 spec §4.7), so
   * it is set on every ask; the column stays nullable for the API's sake. Not a foreign key, but an ask is kept only
   * while its ride is ([AskDao.keep]).
   */
  val rideId: String?,
  /** The conversation's memo (US-D-04.001); null when there is none. */
  val memo: String? = null,
  /** When the memo last changed; null while it has never had one. */
  val memoUpdatedAt: Long? = null,
  /** The memoUpdatedAt the Backend last accepted; null until then. */
  val memoSentUpdatedAt: Long? = null,
  /** The memoUpdatedAt the Backend refused (400 or 404); that version is not sent again (slice 3 spec §5.2). */
  val memoRejectedUpdatedAt: Long? = null,
  // An unreached ask's version and sending markers, as a point memo's (slice 4 spec §4.2); null for the other asks.
  val updatedAt: Long? = null,
  val sentUpdatedAt: Long? = null,
  val rejectedUpdatedAt: Long? = null,
  /** The older point the Backend takes an unreached ask's heading from; null when there was none. */
  val sampleLatitude: Double? = null,
  val sampleLongitude: Double? = null,
  val sampleAt: Long? = null,
)

/**
 * A point memo (slice 3 spec §4.1, US-D-08). Written while its ride records or is paused, and gone with the ride. The
 * place it was written at is null when the ride had no point yet; the sample is the older point the Backend takes the
 * heading from. address and headingDegrees are what the Backend answered; null until it has.
 */
@Entity(
  tableName = "notes",
  indices = [Index(value = ["rideId"])],
  foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
)
data class NoteEntity(
  @PrimaryKey val id: String,
  val rideId: String,
  val at: Long,
  val latitude: Double?,
  val longitude: Double?,
  val sampleLatitude: Double?,
  val sampleLongitude: Double?,
  val sampleAt: Long?,
  val text: String,
  val updatedAt: Long,
  val sentUpdatedAt: Long?,
  val rejectedUpdatedAt: Long?,
  val address: String?,
  val headingDegrees: Double?,
)

/** A record deleted on the device whose deletion has not reached the Backend yet (slice 3 spec §4.2). kind: ask / ride / note. */
@Entity(tableName = "deletions", primaryKeys = ["kind", "id"])
data class DeletionEntity(val kind: String, val id: String, val rideId: String?, val deletedAt: Long)

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
  @Query(
    "SELECT askId, memo, memoUpdatedAt FROM asks WHERE memoUpdatedAt IS NOT NULL AND " +
      "(memoSentUpdatedAt IS NULL OR memoSentUpdatedAt < memoUpdatedAt) AND " +
      "(memoRejectedUpdatedAt IS NULL OR memoRejectedUpdatedAt != memoUpdatedAt) ORDER BY memoUpdatedAt",
  )
  suspend fun unsentMemos(): List<AskMemoRow>

  @Query(
    "UPDATE asks SET memoSentUpdatedAt = :updatedAt WHERE askId = :askId AND (memoSentUpdatedAt IS NULL OR memoSentUpdatedAt < :updatedAt)",
  )
  suspend fun markMemoSent(askId: String, updatedAt: Long)

  @Query("UPDATE asks SET memoRejectedUpdatedAt = :updatedAt WHERE askId = :askId")
  suspend fun markMemoRefused(askId: String, updatedAt: Long)

  // Sending unreached asks (slice 4 spec §5.1): those changed since they were taken, unless that version was refused.
  @Query(
    "SELECT * FROM asks WHERE status = 'offline' AND (sentUpdatedAt IS NULL OR sentUpdatedAt < updatedAt) AND " +
      "(rejectedUpdatedAt IS NULL OR rejectedUpdatedAt != updatedAt) ORDER BY updatedAt",
  )
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
private const val SUMMARY = "SELECT (SELECT COUNT(*) FROM asks WHERE status = 'offline' AND (sentUpdatedAt IS NULL OR sentUpdatedAt < updatedAt) AND " +
  "(rejectedUpdatedAt IS NULL OR rejectedUpdatedAt != updatedAt)) AS unreached, " +
  "(SELECT COUNT(*) FROM rides WHERE status != 'recording' AND ((sentUpdatedAt IS NULL OR sentUpdatedAt < updatedAt) AND " +
  "(rejectedUpdatedAt IS NULL OR rejectedUpdatedAt != updatedAt) OR " +
  "EXISTS (SELECT 1 FROM route_points WHERE chunk IS NULL AND route_points.rideId = rides.id))) AS unsentRides, " +
  "(SELECT COUNT(*) FROM notes WHERE (sentUpdatedAt IS NULL OR sentUpdatedAt < updatedAt) AND " +
  "(rejectedUpdatedAt IS NULL OR rejectedUpdatedAt != updatedAt)) AS notes, " +
  "(SELECT COUNT(*) FROM asks WHERE memoUpdatedAt IS NOT NULL AND (memoSentUpdatedAt IS NULL OR memoSentUpdatedAt < memoUpdatedAt) AND " +
  "(memoRejectedUpdatedAt IS NULL OR memoRejectedUpdatedAt != memoUpdatedAt)) AS memos, " +
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
      "EXISTS (SELECT 1 FROM asks WHERE asks.rideId = rides.id AND asks.memoUpdatedAt IS NOT NULL AND " +
      "(asks.memoSentUpdatedAt IS NULL OR asks.memoSentUpdatedAt < asks.memoUpdatedAt)) AS memosLeft, " +
      "EXISTS (SELECT 1 FROM notes WHERE notes.rideId = rides.id AND " +
      "(notes.sentUpdatedAt IS NULL OR notes.sentUpdatedAt < notes.updatedAt)) AS notesLeft, " +
      "EXISTS (SELECT 1 FROM asks WHERE asks.rideId = rides.id AND asks.status = 'offline' AND " +
      "(asks.sentUpdatedAt IS NULL OR asks.sentUpdatedAt < asks.updatedAt)) AS unreachedLeft FROM rides",
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

  @Query("UPDATE rides SET distanceMeters = :distanceMeters, lastAliveAt = :aliveAt WHERE id = :rideId")
  suspend fun updateDistance(rideId: String, distanceMeters: Double, aliveAt: Long)

  @Query("UPDATE rides SET lastAliveAt = :at WHERE id = :rideId")
  suspend fun markAlive(rideId: String, at: Long)

  // Sending (slice 2 spec §5.3). Points are counted and cut into chunks in the order they were recorded (id).

  @Query(
    "SELECT * FROM rides WHERE ((sentUpdatedAt IS NULL OR sentUpdatedAt < updatedAt) AND " +
      "(rejectedUpdatedAt IS NULL OR rejectedUpdatedAt != updatedAt)) " +
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

data class KeptRideRow(val id: String, val updatedAt: Long, val points: Int)

data class NoteVersion(val id: String, val updatedAt: Long)

data class AskVersion(val askId: String, val updatedAt: Long?, val memoUpdatedAt: Long?)

/** Reading the cloud's records in (slice 4 spec §6.3): what the merge reads of the device, and what it writes. */
@Dao
interface TakeoverDao {
  @Query("SELECT COUNT(*) FROM rides WHERE status != 'ended'")
  suspend fun activeRides(): Int

  @Query("SELECT id, updatedAt, (SELECT COUNT(*) FROM route_points WHERE route_points.rideId = rides.id) AS points FROM rides")
  suspend fun rides(): List<KeptRideRow>

  @Query("SELECT id, updatedAt FROM notes")
  suspend fun notes(): List<NoteVersion>

  @Query("SELECT askId, updatedAt, memoUpdatedAt FROM asks")
  suspend fun asks(): List<AskVersion>

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

@Database(
  entities = [RideEntity::class, SegmentEntity::class, RoutePointEntity::class, AskEntity::class, NoteEntity::class, DeletionEntity::class],
  version = 6,
  exportSchema = true,
)
abstract class RideDatabase : RoomDatabase() {
  abstract fun rides(): RideDao
  abstract fun asks(): AskDao
  abstract fun notes(): NoteDao
  abstract fun takeover(): TakeoverDao

  companion object {
    fun open(context: Context): RideDatabase =
      Room.databaseBuilder(context, RideDatabase::class.java, "rides.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
  }
}

/**
 * 1 -> 2 (slice 2 plan 3a): the asks table, the rides' markers for what the Backend refused, and an index for the
 * points left to send. It only adds, so it stays quick: it runs inside RideController.load(), which the service awaits
 * before startForeground (adr/0006).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `rides` ADD COLUMN `rejectedUpdatedAt` INTEGER")
    db.execSQL("ALTER TABLE `rides` ADD COLUMN `routeRejected` INTEGER NOT NULL DEFAULT 0")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `asks` (`askId` TEXT NOT NULL, `askedAt` INTEGER NOT NULL, `answeredAt` INTEGER, " +
        "`transcript` TEXT, `answer` TEXT, `latitude` REAL, `longitude` REAL, `address` TEXT, `headingDegrees` REAL, " +
        "`input` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `newConversation` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
        "`failure` TEXT, `rideId` TEXT, PRIMARY KEY(`askId`))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_asks_rideId` ON `asks` (`rideId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_asks_askedAt` ON `asks` (`askedAt`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_points_chunk_rideId` ON `route_points` (`chunk`, `rideId`)")
  }
}

/**
 * 2 -> 3 (slice 2 plan 4a, Android #27): a refusal moves from the ride to the points of the refused chunk. Version 2
 * did not keep which chunk was refused, so all the sent points of such a ride count as refused: the ride shows
 * "未送信あり" rather than "保存済み". It only adds, like 1 -> 2.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `route_points` ADD COLUMN `refused` INTEGER NOT NULL DEFAULT 0")
    db.execSQL("UPDATE `route_points` SET `refused` = 1 WHERE `chunk` IS NOT NULL AND `rideId` IN (SELECT `id` FROM `rides` WHERE `routeRejected` = 1)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_points_refused_rideId` ON `route_points` (`refused`, `rideId`)")
  }
}

/**
 * 3 -> 4 (slice 2 plan 4b, adr/0008): asks are taken only while a ride records, and the asks kept without a ride (the
 * test asks of development) go. The schema itself does not change.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("DELETE FROM `asks` WHERE `rideId` IS NULL")
  }
}

/**
 * 4 -> 5 (slice 3 spec §4.1): memos on rides and asks, the point memos and the deletions waiting to be sent. It only
 * adds, like 1 -> 2, so it stays quick inside RideController.load() (adr/0006).
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `rides` ADD COLUMN `memo` TEXT")
    db.execSQL("ALTER TABLE `asks` ADD COLUMN `memo` TEXT")
    db.execSQL("ALTER TABLE `asks` ADD COLUMN `memoUpdatedAt` INTEGER")
    db.execSQL("ALTER TABLE `asks` ADD COLUMN `memoSentUpdatedAt` INTEGER")
    db.execSQL("ALTER TABLE `asks` ADD COLUMN `memoRejectedUpdatedAt` INTEGER")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `rideId` TEXT NOT NULL, `at` INTEGER NOT NULL, `latitude` REAL, " +
        "`longitude` REAL, `sampleLatitude` REAL, `sampleLongitude` REAL, `sampleAt` INTEGER, `text` TEXT NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, `sentUpdatedAt` INTEGER, `rejectedUpdatedAt` INTEGER, `address` TEXT, `headingDegrees` REAL, " +
        "PRIMARY KEY(`id`), FOREIGN KEY(`rideId`) REFERENCES `rides`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_rideId` ON `notes` (`rideId`)")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `deletions` (`kind` TEXT NOT NULL, `id` TEXT NOT NULL, `rideId` TEXT, `deletedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`kind`, `id`))",
    )
  }
}

/**
 * 5 -> 6 (slice 4 spec §4.2): asks that never reached the Backend. Their conversation is null, which SQLite cannot allow
 * on the column in place, so the asks table is made again and its rows copied over. The table is small (one row per
 * ask) and has no foreign keys either way, so this stays quick inside RideController.load() (adr/0006).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `asks_new` (`askId` TEXT NOT NULL, `askedAt` INTEGER NOT NULL, `answeredAt` INTEGER, " +
        "`transcript` TEXT, `answer` TEXT, `latitude` REAL, `longitude` REAL, `address` TEXT, `headingDegrees` REAL, " +
        "`input` TEXT NOT NULL, `conversationId` TEXT, `newConversation` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
        "`failure` TEXT, `rideId` TEXT, `memo` TEXT, `memoUpdatedAt` INTEGER, `memoSentUpdatedAt` INTEGER, " +
        "`memoRejectedUpdatedAt` INTEGER, `updatedAt` INTEGER, `sentUpdatedAt` INTEGER, `rejectedUpdatedAt` INTEGER, " +
        "`sampleLatitude` REAL, `sampleLongitude` REAL, `sampleAt` INTEGER, PRIMARY KEY(`askId`))",
    )
    val kept = "`askId`, `askedAt`, `answeredAt`, `transcript`, `answer`, `latitude`, `longitude`, `address`, `headingDegrees`, " +
      "`input`, `conversationId`, `newConversation`, `status`, `failure`, `rideId`, `memo`, `memoUpdatedAt`, " +
      "`memoSentUpdatedAt`, `memoRejectedUpdatedAt`"
    db.execSQL("INSERT INTO `asks_new` ($kept) SELECT $kept FROM `asks`")
    db.execSQL("DROP TABLE `asks`")
    db.execSQL("ALTER TABLE `asks_new` RENAME TO `asks`")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_asks_rideId` ON `asks` (`rideId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_asks_askedAt` ON `asks` (`askedAt`)")
  }
}
