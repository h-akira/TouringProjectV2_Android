package com.touringproject.v2.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
  /**
   * The older point the Backend took an unreached ask's heading from, on asks kept before slice 5 only; since then the
   * device's heading is in headingDegrees (slice 5 spec §4.4).
   */
  val sampleLatitude: Double? = null,
  val sampleLongitude: Double? = null,
  val sampleAt: Long? = null,
)

/**
 * A point memo (slice 3 spec §4.1, US-D-08). Written while its ride records or is paused, and gone with the ride. The
 * place it was written at is null when the ride had no point yet. headingDegrees is the device's heading (slice 5 spec
 * §4.4) until the Backend answers the one it settled; the sample is left only on memos kept before slice 5. address is
 * what the Backend answered; null until it has.
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
