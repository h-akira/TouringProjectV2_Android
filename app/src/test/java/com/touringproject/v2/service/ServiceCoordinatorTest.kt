package com.touringproject.v2.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServiceCoordinatorTest {
  private val events = mutableListOf<String>()
  private var refuseMicrophone = false
  private val launcher = object : ServiceLauncher {
    override fun start() { events += "start" }
    override fun stop() { events += "stop" }
  }
  private val service = object : RunningService {
    override fun apply(uses: Set<ServiceUse>) {
      if (refuseMicrophone && ServiceUse.MICROPHONE in uses) throw SecurityException("microphone from the background")
      events += "apply:${uses.sorted()}"
    }
  }
  private val coordinator = ServiceCoordinator(launcher) { _, _ -> events += "logged" }

  @Test
  fun the_uses_follow_the_ride_and_the_ask() {
    assertEquals(emptySet<ServiceUse>(), serviceUses(recording = false, asking = false))
    assertEquals(setOf(ServiceUse.LOCATION), serviceUses(recording = true, asking = false))
    assertEquals(setOf(ServiceUse.MICROPHONE, ServiceUse.MEDIA_PLAYBACK), serviceUses(recording = false, asking = true))
    assertEquals(ServiceUse.entries.toSet(), serviceUses(recording = true, asking = true))
  }

  @Test
  fun without_a_running_service_it_is_started_and_stopped() {
    coordinator.setRecording(true)
    coordinator.setRecording(false)
    assertEquals(listOf("start", "stop"), events)
  }

  @Test
  fun a_running_service_changes_its_types_and_an_ask_during_a_ride_adds_the_microphone() {
    coordinator.setRecording(true)
    coordinator.attach(service)
    coordinator.keepAlive.start()
    coordinator.keepAlive.stop()
    coordinator.setRecording(false)
    assertEquals(
      listOf(
        "start",
        "apply:[LOCATION, MICROPHONE, MEDIA_PLAYBACK]",
        "apply:[LOCATION]",
        "apply:[]",
      ),
      events,
    )
  }

  @Test
  fun when_the_microphone_is_refused_the_ask_fails_and_the_ride_keeps_its_location() {
    coordinator.setRecording(true)
    coordinator.attach(service)
    refuseMicrophone = true
    assertThrows(SecurityException::class.java) { coordinator.keepAlive.start() }
    assertEquals(setOf(ServiceUse.LOCATION), coordinator.uses)
    assertEquals(listOf("start", "apply:[LOCATION]"), events)
  }

  @Test
  fun a_refused_start_for_the_ride_is_logged_and_refresh_tries_again() {
    var refuse = true
    val picky = ServiceCoordinator(object : ServiceLauncher {
      override fun start() {
        if (refuse) throw IllegalStateException("not allowed from the background")
        events += "start"
      }
      override fun stop() { events += "stop" }
    }) { _, _ -> events += "logged" }
    picky.setRecording(true)
    refuse = false
    picky.refresh()
    assertEquals(listOf("logged", "start"), events)
  }

  @Test
  fun refresh_leaves_a_running_service_alone() {
    coordinator.setRecording(true)
    coordinator.attach(service)
    events.clear()
    coordinator.refresh()
    assertEquals(emptyList<String>(), events)
  }
}
