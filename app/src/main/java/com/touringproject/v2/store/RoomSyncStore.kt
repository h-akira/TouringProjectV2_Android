package com.touringproject.v2.store

import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.sync.ChunkRange
import com.touringproject.v2.sync.PendingDeletion
import com.touringproject.v2.sync.SyncStore
import com.touringproject.v2.sync.Unsent
import com.touringproject.v2.sync.UnsentAskMemo
import com.touringproject.v2.sync.UnsentNote
import com.touringproject.v2.sync.UnsentUnreached

class RoomSyncStore(
  private val dao: RideDao,
  private val asks: AskDao,
  private val notes: NoteDao,
  private val now: () -> Long = System::currentTimeMillis,
) : SyncStore {
  override suspend fun unsent(): List<Unsent> = dao.unsentSnapshot().map {
    Unsent(it.ride.toRide(it.segments), it.ride.sentUpdatedAt, it.ride.rejectedUpdatedAt, it.sentPoints, it.totalPoints)
  }

  override suspend fun points(rideId: String, from: Int, until: Int): List<RoutePoint> =
    dao.pointsFrom(rideId, from, until - from).map { it.toPoint() }

  override suspend fun rideSent(rideId: String, updatedAt: Long) = dao.markSent(rideId, updatedAt)

  override suspend fun rideRefused(rideId: String, updatedAt: Long) = dao.markRefused(rideId, updatedAt)

  override suspend fun chunkSent(rideId: String, chunk: ChunkRange, refused: Boolean) =
    dao.markChunk(rideId, chunk.seq, chunk.from, chunk.until - chunk.from, refused)

  override suspend fun deletions(): List<PendingDeletion> = dao.deletions().map { PendingDeletion(it.kind, it.id, it.rideId) }

  override suspend fun deletionSent(deletion: PendingDeletion) = dao.deletionSent(deletion.kind, deletion.id)

  override suspend fun unsentAskMemos(): List<UnsentAskMemo> =
    asks.unsentMemos().map { UnsentAskMemo(it.askId, it.memo, it.memoUpdatedAt) }

  override suspend fun askMemoSent(askId: String, updatedAt: Long) = asks.markMemoSent(askId, updatedAt)

  override suspend fun askMemoRefused(askId: String, updatedAt: Long) = asks.markMemoRefused(askId, updatedAt)

  override suspend fun unsentNotes(): List<UnsentNote> = notes.unsent().map {
    UnsentNote(it.id, it.rideId, it.at, it.latitude, it.longitude, it.sampleLatitude, it.sampleLongitude, it.sampleAt, it.text, it.updatedAt, it.headingDegrees)
  }

  override suspend fun noteSent(noteId: String, updatedAt: Long, address: String?, headingDegrees: Double?) =
    notes.markSent(noteId, updatedAt, address, headingDegrees)

  override suspend fun noteRefused(noteId: String, updatedAt: Long) = notes.markRefused(noteId, updatedAt)

  // An unreached ask always has its ride and its version (slice 4 spec §4.2); a row without them is not one to send.
  override suspend fun unsentUnreached(): List<UnsentUnreached> = asks.unsentUnreached().mapNotNull {
    val rideId = it.rideId ?: return@mapNotNull null
    val updatedAt = it.updatedAt ?: return@mapNotNull null
    UnsentUnreached(it.askId, rideId, it.askedAt, it.latitude, it.longitude, it.sampleLatitude, it.sampleLongitude, it.sampleAt, updatedAt, it.headingDegrees)
  }

  override suspend fun unreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?) =
    asks.markUnreachedSent(askId, updatedAt, address, headingDegrees)

  override suspend fun unreachedRefused(askId: String, updatedAt: Long) = asks.markUnreachedRefused(askId, updatedAt)

  override suspend fun retryPlaces() = dao.retryPlaces(now())
}
