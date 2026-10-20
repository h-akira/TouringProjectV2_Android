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
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** An ask whose loop ended with an answer or a failure the Backend reported (slice 2 spec §5.2). */
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
  val conversationId: String,
  /** True for the first ask of a conversation: the conversation breaks just before it (US-C-03.004). */
  val newConversation: Boolean,
  /** answered / failed */
  val status: String,
  /** no_speech / transcribe_failed / agent_failed / tts_failed / timeout; null when answered */
  val failure: String?,
  /** The ride that was recording when the ask started; null when none was. Not a foreign key: a ride's row may be missing for a while after a failed save. */
  val rideId: String?,
)

@Dao
interface AskDao {
  @Upsert
  suspend fun upsert(ask: AskEntity)

  @Query("SELECT * FROM asks WHERE askId = :askId")
  suspend fun ask(askId: String): AskEntity?

  /** How many of the ride's asks are kept, answered or failed; follows every new one (SC-02, SC-04). */
  @Query("SELECT COUNT(*) FROM asks WHERE rideId = :rideId")
  fun countForRide(rideId: String): Flow<Int>

  /** The ride's asks in the order they were asked (SC-06, SC-07). */
  @Query("SELECT * FROM asks WHERE rideId = :rideId ORDER BY askedAt")
  fun forRide(rideId: String): Flow<List<AskEntity>>
}

data class RideWithSegments(
  @Embedded val ride: RideEntity,
  @Relation(parentColumn = "id", entityColumn = "rideId") val segments: List<SegmentEntity>,
)

/** A ride as SC-05 lists it. pointsLeft is true while one of its points has not been taken (unsent or refused). */
data class RideRow(
  @Embedded val ride: RideEntity,
  @Relation(parentColumn = "id", entityColumn = "rideId") val segments: List<SegmentEntity>,
  val questions: Int,
  val pointsLeft: Boolean,
)

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
      "EXISTS (SELECT 1 FROM route_points WHERE refused = 1 AND route_points.rideId = rides.id)) AS pointsLeft FROM rides",
  )
  fun rows(): Flow<List<RideRow>>

  @Transaction
  @Query("SELECT * FROM rides WHERE id = :id")
  fun watch(id: String): Flow<RideWithSegments?>

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
}

@Database(entities = [RideEntity::class, SegmentEntity::class, RoutePointEntity::class, AskEntity::class], version = 3, exportSchema = true)
abstract class RideDatabase : RoomDatabase() {
  abstract fun rides(): RideDao
  abstract fun asks(): AskDao

  companion object {
    fun open(context: Context): RideDatabase =
      Room.databaseBuilder(context, RideDatabase::class.java, "rides.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
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
