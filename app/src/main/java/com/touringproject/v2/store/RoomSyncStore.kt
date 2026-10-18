package com.touringproject.v2.store

import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.sync.ChunkRange
import com.touringproject.v2.sync.SyncStore
import com.touringproject.v2.sync.Unsent

class RoomSyncStore(private val dao: RideDao) : SyncStore {
  override suspend fun unsent(): List<Unsent> = dao.unsentRides().map {
    Unsent(it.toRide(dao.segments(it.id)), it.sentUpdatedAt, it.rejectedUpdatedAt, dao.sentPointCount(it.id), dao.pointCount(it.id))
  }

  override suspend fun points(rideId: String, from: Int, until: Int): List<RoutePoint> =
    dao.pointsFrom(rideId, from, until - from).map { it.toPoint() }

  override suspend fun rideSent(rideId: String, updatedAt: Long) = dao.markSent(rideId, updatedAt)

  override suspend fun rideRefused(rideId: String, updatedAt: Long) = dao.markRefused(rideId, updatedAt)

  override suspend fun chunkSent(rideId: String, chunk: ChunkRange, refused: Boolean) {
    dao.markChunk(rideId, chunk.seq, chunk.from, chunk.until - chunk.from)
    if (refused) dao.markRouteRefused(rideId)
  }
}
