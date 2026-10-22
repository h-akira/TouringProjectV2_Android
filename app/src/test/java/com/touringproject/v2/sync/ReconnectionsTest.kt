package com.touringproject.v2.sync

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// Slice 4 spec §5.2: the network coming back is a trigger, so what waited for it goes without the app in front.
class ReconnectionsTest {
  @Test
  fun only_a_change_from_no_network_to_one_is_a_trigger() = runTest {
    assertEquals(2, reconnections(flowOf(true, false, true, true, false, false, true)).toList().size)
  }

  // The state the app starts in is not a change, even when it starts without a network and stays so.
  @Test
  fun the_state_at_the_start_is_not_a_trigger() = runTest {
    assertEquals(0, reconnections(flowOf(true)).toList().size)
    assertEquals(0, reconnections(flowOf(false)).toList().size)
    assertEquals(1, reconnections(flowOf(false, true)).toList().size)
  }
}
