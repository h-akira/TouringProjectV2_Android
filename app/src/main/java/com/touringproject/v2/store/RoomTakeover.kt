package com.touringproject.v2.store

import androidx.room.withTransaction
import com.touringproject.v2.api.CloudRecord
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.stepMeters
import com.touringproject.v2.sync.BlockReason
import com.touringproject.v2.sync.DeviceRecords
import com.touringproject.v2.sync.KeptAsk
import com.touringproject.v2.sync.KeptRide
import com.touringproject.v2.sync.Merge
import com.touringproject.v2.sync.TakeResult
import com.touringproject.v2.sync.TakenAsk
import com.touringproject.v2.sync.TakeoverStore
import com.touringproject.v2.sync.merge

/**
 * Takes the cloud's records in (slice 4 spec §6.3). The device is read, merged with the cloud and written in one
 * transaction, so a write that fails, or a cloud record that cannot be read, leaves the device as it was. What comes in
 * is marked as sent, so it is not sent back. waitingToSave tells whether a ride's change waits in memory to be written
 * again (RideController); for a change of user name it counts as left to send (Android #72).
 */
class RoomTakeover(private val db: RideDatabase, private val waitingToSave: () -> Boolean = { false }) : TakeoverStore {
  private val takeover = db.takeover()
  private val rides = db.rides()
  private val asks = db.asks()

  override suspend fun take(cloud: List<CloudRecord>, replace: Boolean, now: Long): TakeResult = db.withTransaction {
    if (takeover.activeRides() > 0) return@withTransaction TakeResult.Blocked(BlockReason.RIDE)
    if (replace) {
      if (rides.summaryNow().unsent > 0 || waitingToSave()) return@withTransaction TakeResult.Blocked(BlockReason.UNSENT)
      takeover.clearAll()
    }
    val merged = merge(device(), cloud, now)
    write(merged)
    TakeResult.Done(merged.count)
  }

  private suspend fun device(): DeviceRecords {
    val notes = takeover.notes()
    val asks = takeover.asks()
    return DeviceRecords(
      rides = takeover.rides().associate { it.id to KeptRide(it.updatedAt, it.points) },
      notes = notes.associate { it.id to it.updatedAt },
      asks = asks.associate { it.askId to KeptAsk(it.updatedAt, it.memoUpdatedAt) },
      deletions = rides.deletions().map { it.kind to it.id }.toSet(),
      rideOf = notes.associate { it.id to it.rideId } + asks.mapNotNull { a -> a.rideId?.let { a.askId to it } },
    )
  }

  // A ride the cloud removed goes with all of it, as a deletion on the device does, but leaves no mark to send.
  private suspend fun write(merged: Merge) {
    for (rideId in merged.gone.rides) {
      rides.deleteAsks(rideId)
      rides.deleteNotes(rideId)
      rides.deletePoints(rideId)
      rides.deleteSegments(rideId)
      rides.deleteRideRow(rideId)
    }
    merged.gone.asks.forEach { asks.deleteRow(it) }
    merged.gone.notes.forEach { db.notes().deleteRow(it) }
    for (taken in merged.rides) {
      val ride = taken.ride
      val saved = rides.ride(ride.id)
      rides.upsertRide(ride.toEntity().copy(sentUpdatedAt = taken.sentUpdatedAt, distanceMeters = saved?.distanceMeters ?: 0.0))
      rides.deleteSegments(ride.id)
      rides.insertSegments(ride.segments.map { SegmentEntity(ride.id, it.seq, it.startedAt, it.endedAt, it.bootCount) })
    }
    for (route in merged.routes) {
      rides.deletePoints(route.rideId)
      takeover.insertPoints(
        route.points.map {
          val p = it.point
          RoutePointEntity(rideId = route.rideId, segment = p.segment, t = p.t, lat = p.lat, lon = p.lon, accuracy = p.accuracy, chunk = it.chunk)
        },
      )
      takeover.setDistance(route.rideId, distance(rides.points(route.rideId).map { it.toPoint() }))
    }
    for (note in merged.notes) {
      takeover.upsertNote(
        NoteEntity(
          note.id, note.rideId, note.at, note.latitude, note.longitude, null, null, null, note.text, note.updatedAt,
          sentUpdatedAt = note.updatedAt, rejectedUpdatedAt = null, address = note.address, headingDegrees = note.headingDegrees,
        ),
      )
    }
    merged.asks.forEach { asks.upsert(row(it, asks.ask(it.ask.askId))) }
  }

  /**
   * The ask as it is written: whole when the device does not keep it; otherwise the cloud's ask itself (content) and
   * the cloud's memo (memo), each in place of the device's only when the merge took it.
   */
  private fun row(taken: TakenAsk, kept: AskEntity?): AskEntity {
    val cloud = taken.ask.toEntity().copy(
      memo = taken.ask.memo,
      memoUpdatedAt = taken.memoUpdatedAt,
      memoSentUpdatedAt = taken.memoUpdatedAt,
      sentUpdatedAt = taken.ask.updatedAt,
    )
    if (kept == null) return cloud
    val asked = if (taken.content) {
      cloud.copy(memo = kept.memo, memoUpdatedAt = kept.memoUpdatedAt, memoSentUpdatedAt = kept.memoSentUpdatedAt, memoRejectedUpdatedAt = kept.memoRejectedUpdatedAt)
    } else {
      kept
    }
    if (!taken.memo) return asked
    return asked.copy(memo = cloud.memo, memoUpdatedAt = cloud.memoUpdatedAt, memoSentUpdatedAt = cloud.memoSentUpdatedAt, memoRejectedUpdatedAt = null)
  }
}

private fun distance(points: List<RoutePoint>): Double = points.zipWithNext { a, b -> stepMeters(a, b) }.sum()
