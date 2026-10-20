package com.touringproject.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
  private var opened = 0
  private val readiness = Readiness { opened++ }

  @Test
  fun opens_once_the_settings_the_ride_and_the_permission_answer_have_all_arrived() {
    readiness.settingsLoaded()
    readiness.ridesLoaded()
    assertFalse(readiness.isReady.value)
    readiness.permissionsAnswered()
    assertTrue(readiness.isReady.value)
    assertEquals(1, opened)
  }

  @Test
  fun the_order_does_not_matter_and_it_opens_only_once() {
    readiness.permissionsAnswered()
    readiness.ridesLoaded()
    readiness.settingsLoaded()
    readiness.permissionsAnswered()
    assertTrue(readiness.isReady.value)
    assertEquals(1, opened)
  }

  // Android #38: a restored SC-04 sheet closes itself if the screen draws before the active ride has been read.
  @Test
  fun does_not_open_before_the_active_ride_has_been_read() {
    readiness.settingsLoaded()
    readiness.permissionsAnswered()
    assertFalse(readiness.isReady.value)
    assertEquals(0, opened)
    readiness.ridesLoaded()
    assertTrue(readiness.isReady.value)
  }
}
