package com.touringproject.v2.records

import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.startedAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

// What SC-05 lists: the kept rides and how far each has reached the Backend (slice 2 spec §5.4, §7.4).

enum class SaveState { RECORDING, UNSENT, SAVED }

/**
 * "記録中" while the ride records or is paused; "未送信あり" while its newest version has not been taken (a refused one
 * included) or something of it has not gone or was refused (pointsLeft: a point, or the memo of one of its asks;
 * slice 3 spec §5.3); "保存済み" otherwise (slice 2 spec §5.4).
 */
fun saveState(status: RideStatus, updatedAt: Long, sentUpdatedAt: Long?, pointsLeft: Boolean): SaveState = when {
  status != RideStatus.ENDED -> SaveState.RECORDING
  pointsLeft || sentUpdatedAt == null || sentUpdatedAt < updatedAt -> SaveState.UNSENT
  else -> SaveState.SAVED
}

/** questions counts the asks kept for the ride, answered or failed. */
data class RideListItem(val ride: Ride, val questions: Int, val saveState: SaveState)

/**
 * How many asks the active ride has kept, following the ride (SC-02, SC-04). AppGraph holds it as a StateFlow, so a
 * screen made again shows the count at once instead of 0 (Android #35).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun activeAskCount(activeId: Flow<String?>, count: (String) -> Flow<Int>): Flow<Int> =
  activeId.distinctUntilChanged().flatMapLatest { id -> id?.let(count) ?: flowOf(0) }

/** The ride recording or paused comes first, then the others, newest first (SC-05). */
fun orderRides(items: List<RideListItem>): List<RideListItem> =
  items.sortedWith(compareBy<RideListItem> { it.ride.status == RideStatus.ENDED }.thenByDescending { it.ride.startedAt() })
