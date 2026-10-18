package com.touringproject.v2.store

import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RideStore
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.Segment

private fun RideStatus.column() = name.lowercase()
private fun EndReason.column() = name.lowercase()

fun Ride.toEntity() = RideEntity(id, name, status.column(), endReason?.column(), deadline, lastAliveAt, distanceMeters, updatedAt, null)

fun RideEntity.toRide(segments: List<SegmentEntity>) = Ride(
  id = id,
  name = name,
  status = RideStatus.valueOf(status.uppercase()),
  segments = segments.map { Segment(it.seq, it.startedAt, it.endedAt, it.bootCount) },
  endReason = endReason?.let { EndReason.valueOf(it.uppercase()) },
  deadline = deadline,
  lastAliveAt = lastAliveAt,
  distanceMeters = distanceMeters,
  updatedAt = updatedAt,
)

fun RoutePointEntity.toPoint() = RoutePoint(segment, t, lat, lon, accuracy)

class RoomRideStore(private val dao: RideDao) : RideStore {
  override suspend fun active(): Ride? = dao.active()?.let { it.toRide(dao.segments(it.id)) }

  override suspend fun save(ride: Ride) =
    dao.saveRide(ride.toEntity(), ride.segments.map { SegmentEntity(ride.id, it.seq, it.startedAt, it.endedAt, it.bootCount) })

  override suspend fun lastPoint(rideId: String): RoutePoint? = dao.lastPoint(rideId)?.toPoint()

  override suspend fun addPoint(rideId: String, point: RoutePoint, distanceMeters: Double, aliveAt: Long) =
    dao.addPoint(RoutePointEntity(rideId = rideId, segment = point.segment, t = point.t, lat = point.lat, lon = point.lon, accuracy = point.accuracy, chunk = null), distanceMeters, aliveAt)

  override suspend fun markAlive(rideId: String, at: Long) = dao.markAlive(rideId, at)
}
