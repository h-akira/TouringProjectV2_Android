package com.touringproject.v2.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// Slice 4 spec §5.2: the network coming back is a trigger, so what waited for it goes without the app in front.
class ReconnectionsTest {
  @Test
  fun each_rise_of_the_count_of_comebacks_is_a_trigger() = runTest {
    assertEquals(2, reconnections(flowOf(0, 1, 1, 2)).toList().size)
  }

  // The count the app starts with is not a comeback, whatever it is.
  @Test
  fun the_count_at_the_start_is_not_a_trigger() = runTest {
    assertEquals(0, reconnections(flowOf(0)).toList().size)
    assertEquals(0, reconnections(flowOf(3)).toList().size)
    assertEquals(1, reconnections(flowOf(3, 4)).toList().size)
  }

  // Android #69: a drop and comeback before the collector runs still triggers once; two of them are one trigger.
  @Test
  fun comebacks_the_collector_did_not_see_happen_still_trigger() = runTest {
    val count = MutableStateFlow(0)
    var triggers = 0
    backgroundScope.launch { reconnections(count).collect { triggers += 1 } }
    runCurrent()
    count.value = 1
    count.value = 2
    runCurrent()
    assertEquals(1, triggers)
    count.value = 3
    runCurrent()
    assertEquals(2, triggers)
  }
}
