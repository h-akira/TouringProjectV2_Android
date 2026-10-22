package com.touringproject.v2.store

import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.records.NoteRecord
import com.touringproject.v2.records.RideListItem
import com.touringproject.v2.records.orderRides
import com.touringproject.v2.records.saveState
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RoutePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun AskEntity.toRecord() = AskRecord(
  askId = askId,
  askedAt = askedAt,
  answeredAt = answeredAt,
  transcript = transcript,
  answer = answer,
  latitude = latitude,
  longitude = longitude,
  address = address,
  headingDegrees = headingDegrees,
  input = input,
  conversationId = conversationId,
  newConversation = newConversation,
  outcome = AskOutcome.valueOf(status.uppercase()),
  failure = failure,
  rideId = rideId,
  memo = memo,
)

fun NoteEntity.toRecord() = NoteRecord(id, rideId, at, latitude, longitude, text, address, sent = sentUpdatedAt != null)

/** What the records screens read (SC-05, SC-06, SC-07, SC-19). Each Flow follows the database as it changes. */
class RoomRecords(private val rides: RideDao, private val asks: AskDao, private val notes: NoteDao) {
  val list: Flow<List<RideListItem>> = rides.rows().map { rows ->
    orderRides(
      rows.map { row ->
        val ride = row.ride.toRide(row.segments.sortedBy { it.seq })
        RideListItem(ride, row.questions, saveState(ride.status, ride.updatedAt, row.ride.sentUpdatedAt, row.pointsLeft || row.memosLeft))
      },
    )
  }

  fun ride(id: String): Flow<Ride?> = rides.watch(id).map { it?.ride?.toRide(it.segments.sortedBy { s -> s.seq }) }

  fun asks(rideId: String): Flow<List<AskRecord>> = asks.forRide(rideId).map { list -> list.map { it.toRecord() } }

  fun notes(rideId: String): Flow<List<NoteRecord>> = notes.forRide(rideId).map { list -> list.map { it.toRecord() } }

  /** Read once when the ride is opened: the route of a ride still recording does not follow it. */
  suspend fun route(rideId: String): List<RoutePoint> = rides.points(rideId).map { it.toPoint() }
}
