package com.touringproject.v2.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Android R10-01 / R1-01 / R1-03: the audio route goes to one user at a time, and a user that is called off leaves
// nothing held.
class RouteLeasesTest {
  private class Driver : RouteDriver {
    val log = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var error: Exception? = null
    override suspend fun start(source: Source): Boolean {
      log += "start:${source.name.lowercase()}"
      error?.let { throw it }
      gate?.await()
      return true
    }
    override suspend fun stop() { log += "stop" }
    override fun inputDevice(): InputDevice? = null
  }

  @Test
  fun a_second_lease_waits_until_the_first_is_let_go() = runTest {
    val driver = Driver()
    val leases = RouteLeases(driver)
    val first = leases.lease(Source.HANDSFREE)
    var second: RouteLease? = null
    launch { second = leases.lease(Source.SCREEN) }
    runCurrent()
    assertNull(second)
    assertEquals(listOf("start:handsfree"), driver.log)
    first.release()
    runCurrent()
    assertNotNull(second)
    assertEquals(listOf("start:handsfree", "stop", "start:screen"), driver.log)
  }

  @Test
  fun a_lease_let_go_of_twice_acts_once_and_never_lets_go_of_the_next_ones_route() = runTest {
    val driver = Driver()
    val leases = RouteLeases(driver)
    val first = leases.lease(Source.HANDSFREE)
    first.release()
    val second = leases.lease(Source.HANDSFREE)
    // The cleanup of the first, running late.
    first.release()
    assertEquals(listOf("start:handsfree", "stop", "start:handsfree"), driver.log)
    second.release()
    assertEquals(listOf("start:handsfree", "stop", "start:handsfree", "stop"), driver.log)
  }

  @Test
  fun a_setup_that_is_cancelled_holds_nothing_and_does_not_stop_a_route_it_never_brought_up() = runTest {
    val driver = Driver().apply { gate = CompletableDeferred() }
    val leases = RouteLeases(driver)
    val job = launch { leases.lease(Source.HANDSFREE) }
    runCurrent()
    job.cancel()
    runCurrent()
    driver.gate = null
    val next = leases.lease(Source.SCREEN)
    assertTrue(next.routed)
    assertEquals(listOf("start:handsfree", "start:screen"), driver.log)
  }

  @Test
  fun a_wait_that_is_cancelled_leaves_the_route_to_its_holder() = runTest {
    val driver = Driver()
    val leases = RouteLeases(driver)
    val first = leases.lease(Source.HANDSFREE)
    val waiting = launch { leases.lease(Source.SCREEN) }
    runCurrent()
    waiting.cancel()
    runCurrent()
    first.release()
    // Nobody is waiting any more: the route is free for the next one at once.
    leases.lease(Source.SCREEN)
    assertEquals(listOf("start:handsfree", "stop", "start:screen"), driver.log)
  }

  @Test
  fun a_setup_that_throws_holds_nothing() = runTest {
    val driver = Driver().apply { error = SecurityException("BLUETOOTH_CONNECT") }
    val leases = RouteLeases(driver)
    val thrown = try {
      leases.lease(Source.HANDSFREE)
      null
    } catch (e: SecurityException) {
      e
    }
    assertNotNull(thrown)
    driver.error = null
    assertTrue(leases.lease(Source.HANDSFREE).routed)
  }

  @Test
  fun a_holder_that_never_lets_go_is_given_up_on_after_the_wait() = runTest {
    val leases = RouteLeases(Driver(), waitMs = 5_000)
    leases.lease(Source.HANDSFREE)
    var thrown: Exception? = null
    launch {
      try {
        leases.lease(Source.SCREEN)
      } catch (e: RouteBusyException) {
        thrown = e
      }
    }
    runCurrent()
    advanceTimeBy(4_999)
    assertNull(thrown)
    advanceTimeBy(2)
    runCurrent()
    assertNotNull(thrown)
  }

  @Test
  fun a_request_called_off_during_the_setup_gives_none() = runTest {
    val driver = Driver().apply { gate = CompletableDeferred() }
    val request = LeaseRequest(RouteLeases(driver), Source.HANDSFREE)
    var taken: RouteLease? = null
    var finished = false
    launch {
      taken = request.take()
      finished = true
    }
    runCurrent()
    request.callOff()
    runCurrent()
    assertTrue(finished)
    assertNull(taken)
  }

  @Test
  fun a_request_called_off_before_it_started_gives_none_and_one_called_off_late_changes_nothing() = runTest {
    val early = LeaseRequest(RouteLeases(Driver()), Source.HANDSFREE)
    early.callOff()
    assertNull(early.take())
    val late = LeaseRequest(RouteLeases(Driver()), Source.HANDSFREE)
    val lease = late.take()
    late.callOff()
    assertNotNull(lease)
    assertNull(lease!!.inputDevice())
  }
}
