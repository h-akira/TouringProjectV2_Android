package com.touringproject.v2.handsfree

import org.junit.Assert.assertEquals
import org.junit.Test

class PressTest {
  @Test
  fun each_press_is_handled_once() {
    val t = PressTracker()
    assertEquals(Claim.NEW, t.claim(1_000, 1_000))
    assertEquals(Claim.DUPLICATE, t.claim(1_000, 1_500))
    assertEquals(Claim.NEW, t.claim(2_000, 2_000))
  }

  @Test
  fun old_presses_are_dropped() {
    assertEquals(Claim.STALE, PressTracker().claim(1_000, 1_000 + STALE_PRESS_MS + 1))
  }

  @Test
  fun a_press_before_the_app_is_ready_is_handled_once_it_is() {
    var handled = 0
    val gate = PressGate { handled++ }
    gate.offer(1_000, 1_000)
    assertEquals(0, handled)
    gate.openGate(2_000)
    assertEquals(1, handled)
  }

  @Test
  fun several_early_presses_start_one_question() {
    var handled = 0
    val gate = PressGate { handled++ }
    gate.offer(1_000, 1_000)
    gate.offer(1_200, 1_200)
    gate.openGate(2_000)
    gate.offer(1_200, 2_100)
    assertEquals(1, handled)
  }

  @Test
  fun an_early_press_that_went_stale_while_waiting_is_dropped() {
    var handled = 0
    val gate = PressGate { handled++ }
    gate.offer(1_000, 1_000)
    gate.openGate(1_000 + STALE_PRESS_MS + 1)
    assertEquals(0, handled)
  }

  @Test
  fun after_opening_presses_go_straight_through_and_duplicates_are_dropped() {
    var handled = 0
    val gate = PressGate { handled++ }
    gate.openGate(0)
    gate.offer(1_000, 1_000)
    gate.offer(1_000, 1_100)
    gate.offer(2_000, 2_000)
    assertEquals(2, handled)
  }
}
