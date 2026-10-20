package com.touringproject.v2.records

import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordsTest {
  @Test
  fun a_ride_recording_or_paused_is_recording_whatever_has_been_sent() {
    assertEquals(SaveState.RECORDING, saveState(RideStatus.RECORDING, 5, 5, pointsLeft = false))
    assertEquals(SaveState.RECORDING, saveState(RideStatus.PAUSED, 5, null, pointsLeft = true))
  }

  @Test
  fun an_ended_ride_is_saved_only_once_its_newest_version_and_every_point_have_been_taken() {
    assertEquals(SaveState.SAVED, saveState(RideStatus.ENDED, 5, 5, pointsLeft = false))
    assertEquals(SaveState.UNSENT, saveState(RideStatus.ENDED, 5, null, pointsLeft = false))
    assertEquals(SaveState.UNSENT, saveState(RideStatus.ENDED, 6, 5, pointsLeft = false))
    assertEquals(SaveState.UNSENT, saveState(RideStatus.ENDED, 5, 5, pointsLeft = true))
  }

  @Test
  fun the_active_ride_comes_first_then_the_newest() {
    val old = newRide("old", "箱根", 1_000, 7, 24 * HOUR_MS).end(2_000, EndReason.MANUAL, 2_000)
    val new = newRide("new", "御殿場", 5_000, 7, 24 * HOUR_MS).end(6_000, EndReason.MANUAL, 6_000)
    val active = newRide("active", "富士", 3_000, 7, 24 * HOUR_MS)
    val items = listOf(old, active, new).map { RideListItem(it, 0, SaveState.SAVED) }
    assertEquals(listOf("active", "new", "old"), orderRides(items).map { it.ride.id })
  }
}
