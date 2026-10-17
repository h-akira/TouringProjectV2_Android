package com.touringproject.v2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.touringproject.v2.ask.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// On the emulator: the intercom's assistant button arrives as VOICE_COMMAND and starts exactly one ask
// (4a #1 and 4b #9 of the slice 1 checklist). Without an API key the ask ends at once with "no key".
// MainActivity is singleTask, which ActivityScenario cannot follow, so the activity is started directly.
@RunWith(AndroidJUnit4::class)
class VoiceCommandTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = ApplicationProvider.getApplicationContext<TouringApp>()

  @Before
  fun grantPermissions() {
    listOf(
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
      Manifest.permission.BLUETOOTH_CONNECT,
      Manifest.permission.POST_NOTIFICATIONS,
    ).forEach { instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it) }
  }

  @After
  fun closeActivity() {
    resumed()?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
  }

  private fun voiceCommand() = Intent(app, MainActivity::class.java)
    .setAction(Intent.ACTION_VOICE_COMMAND)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  private fun waitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
      check(SystemClock.uptimeMillis() < deadline) { "condition never became true" }
      SystemClock.sleep(50)
    }
  }

  private fun runs(): Int {
    var n = 0
    instrumentation.runOnMainSync { n = app.graph.controller.runsStarted }
    return n
  }

  private fun resumed(): Activity? {
    var activity: Activity? = null
    instrumentation.runOnMainSync {
      activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return activity
  }

  @Test
  fun one_press_starts_one_ask_even_when_the_activity_is_recreated() {
    val before = runs()
    app.startActivity(voiceCommand())
    waitUntil { runs() == before + 1 }
    waitUntil { resumed() is MainActivity }
    val activity = resumed()!!
    instrumentation.runOnMainSync { activity.recreate() }
    waitUntil { resumed().let { it is MainActivity && it !== activity } }
    SystemClock.sleep(1_000)
    assertEquals(before + 1, runs())
  }

  @Test
  fun a_press_after_back_starts_one_more_ask() {
    val before = runs()
    app.startActivity(voiceCommand())
    waitUntil { runs() == before + 1 }
    waitUntil { resumed() is MainActivity }
    // The ask screen swallows Back, so wait for the "no key" message to end first.
    waitUntil { app.graph.state.value.phase == Phase.IDLE }
    // Back closes the activity but the process stays (4b #9).
    val activity = resumed() as MainActivity
    instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
    waitUntil { activity.isDestroyed || activity.isFinishing }
    app.startActivity(voiceCommand())
    waitUntil { runs() == before + 2 }
    SystemClock.sleep(1_000)
    assertEquals(before + 2, runs())
  }
}
