package com.touringproject.v2.records

import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.startedAt

// What SC-05 lists: the kept rides and how far each has reached the Backend (slice 2 spec §5.4, §7.4).

enum class SaveState { RECORDING, UNSENT, SAVED }

/**
 * "記録中" while the ride records or is paused; "未送信あり" while its newest version has not been taken (a refused one
 * included) or a point has not gone or was refused; "保存済み" otherwise (slice 2 spec §5.4).
 */
fun saveState(status: RideStatus, updatedAt: Long, sentUpdatedAt: Long?, pointsLeft: Boolean): SaveState = when {
  status != RideStatus.ENDED -> SaveState.RECORDING
  pointsLeft || sentUpdatedAt == null || sentUpdatedAt < updatedAt -> SaveState.UNSENT
  else -> SaveState.SAVED
}

/** questions counts the asks kept for the ride, answered or failed. */
data class RideListItem(val ride: Ride, val questions: Int, val saveState: SaveState)

/** The ride recording or paused comes first, then the others, newest first (SC-05). */
fun orderRides(items: List<RideListItem>): List<RideListItem> =
  items.sortedWith(compareBy<RideListItem> { it.ride.status == RideStatus.ENDED }.thenByDescending { it.ride.startedAt() })
