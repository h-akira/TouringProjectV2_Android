package com.touringproject.v2

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.touringproject.v2.ask.Phase
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// On the emulator (slice 2 spec §9): a ride keeps taking in its route with the screen off, and an ask during the ride
// adds the microphone to the running service. Positions come from Fused's mock mode, so the app is made the mock
// location app first.
@RunWith(AndroidJUnit4::class)
class RideRecordingTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = ApplicationProvider.getApplicationContext<TouringApp>()
  private val fused = LocationServices.getFusedLocationProviderClient(app)

  private fun shell(command: String) {
    instrumentation.uiAutomation.executeShellCommand(command).close()
    SystemClock.sleep(300)
  }

  @Before
  fun setUp() {
    listOf(
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
      Manifest.permission.BLUETOOTH_CONNECT,
      Manifest.permission.POST_NOTIFICATIONS,
    ).forEach { instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it) }
    shell("appops set ${app.packageName} android:mock_location allow")
    Tasks.await(fused.setMockMode(true))
    shell("input keyevent KEYCODE_WAKEUP")
    // A location service can only start while the app is in front (Android 14+).
    app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    waitUntil { resumed() is MainActivity }
    waitUntil { app.graph.isReady.value }
  }

  @After
  fun tearDown() {
    onMain { app.graph.controller.cancel() }
    onMain { app.graph.scope.launch { app.graph.rides.end("emulator") } }
    waitUntil { app.graph.rides.active.value == null }
    // VoiceCommandTest relies on there being no key.
    runBlocking { app.graph.apiKeys.save(null) }
    Tasks.await(fused.setMockMode(false))
    shell("input keyevent KEYCODE_WAKEUP")
    resumed()?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
  }

  private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

  private fun waitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
      check(SystemClock.uptimeMillis() < deadline) { "condition never became true" }
      SystemClock.sleep(100)
    }
  }

  private fun resumed(): Activity? {
    var activity: Activity? = null
    onMain { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
    return activity
  }

  private fun serviceTypes(): Int? {
    var types: Int? = null
    onMain { types = (app.graph.serviceCoordinator.running as? Service)?.foregroundServiceType }
    return types
  }

  private fun startRide() {
    onMain { app.graph.scope.launch { app.graph.rides.start("emulator") } }
    waitUntil { serviceTypes() == ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION }
  }

  private fun mock(lat: Double) {
    val location = Location("fused").apply {
      latitude = lat
      longitude = 139.0
      accuracy = 5f
      time = System.currentTimeMillis()
      elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
    Tasks.await(fused.setMockLocation(location))
  }

  @Test
  fun the_route_keeps_coming_in_with_the_screen_off() {
    startRide()
    shell("input keyevent KEYCODE_SLEEP")
    // One point every 5 s, about 111 m apart, for a minute.
    repeat(12) { i ->
      mock(35.0 + i * 0.001)
      SystemClock.sleep(5_000)
    }
    shell("input keyevent KEYCODE_WAKEUP")
    val distance = app.graph.rides.active.value!!.distanceMeters
    assertTrue("distance was $distance", distance > 800)
  }

  @Test
  fun an_ask_during_a_ride_adds_the_microphone_and_gives_it_back() {
    assumeTrue("needs trv2.apiBaseUrl in local.properties", app.graph.apiBaseUrl.isNotEmpty())
    // A key is needed for the ask to reach the service; the ask is cancelled before anything is sent.
    runBlocking { app.graph.apiKeys.save("emulator-test-key") }
    startRide()
    app.startActivity(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    waitUntil { app.graph.state.value.phase == Phase.RECORDING }
    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      serviceTypes(),
    )
    onMain { app.graph.controller.cancel() }
    waitUntil { serviceTypes() == ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION }
  }
}
