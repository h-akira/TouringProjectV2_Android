package com.touringproject.v2.store

import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.sync.ChunkRange
import com.touringproject.v2.sync.PendingDeletion
import com.touringproject.v2.sync.SyncStore
import com.touringproject.v2.sync.Unsent
import com.touringproject.v2.sync.UnsentAskMemo

class RoomSyncStore(private val dao: RideDao, private val asks: AskDao) : SyncStore {
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
}
