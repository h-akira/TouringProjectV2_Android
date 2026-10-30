package com.touringproject.v2.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameChangeTest {
  @Test
  fun a_ride_stops_it_before_anything_else() {
    assertEquals(BlockReason.RIDE, nameChangeBlock(rideActive = true, unsent = 3, waitingToSave = true))
  }

  @Test
  fun what_is_left_to_send_stops_it_before_a_save_waiting() {
    assertEquals(BlockReason.UNSENT, nameChangeBlock(rideActive = false, unsent = 1, waitingToSave = true))
  }

  @Test
  fun a_save_waiting_stops_it_alone() {
    assertEquals(BlockReason.WAITING, nameChangeBlock(rideActive = false, unsent = 0, waitingToSave = true))
  }

  @Test
  fun nothing_in_the_way_lets_it_go() {
    assertNull(nameChangeBlock(rideActive = false, unsent = 0, waitingToSave = false))
  }
}
