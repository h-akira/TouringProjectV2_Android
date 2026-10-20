package com.touringproject.v2

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
  private val app = ApplicationProvider.getApplicationContext<TouringApp>()

  private fun voiceCommand() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VOICE_COMMAND)

  private fun waitUntil(condition: () -> Boolean) {
    repeat(200) {
      if (condition()) return
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(10)
    }
    error("condition never became true")
  }

  @Test
  fun the_intercom_button_starts_one_ask_even_when_the_activity_is_recreated() {
    val graph = app.graph
    ActivityScenario.launch<MainActivity>(voiceCommand()).use { scenario ->
      // The press waits until the permission prompt has been answered and the settings are loaded.
      assertEquals(0, graph.controller.runsStarted)
      graph.permissionsAnswered()
      waitUntil { graph.controller.runsStarted == 1 }
      scenario.recreate()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(1, graph.controller.runsStarted)
    }
  }

  @Test
  fun reopening_from_recents_does_not_count_the_old_press_again() {
    val graph = app.graph
    val fromRecents = voiceCommand().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
    ActivityScenario.launch<MainActivity>(fromRecents).use {
      graph.permissionsAnswered()
      waitUntil { graph.isReady.value }
      repeat(20) { shadowOf(Looper.getMainLooper()).idle() }
      assertEquals(0, graph.controller.runsStarted)
    }
  }

  // Android #38: after process death the activity comes back with saved state, but this process has not asked yet.
  @Test
  fun an_activity_restored_in_a_new_process_asks_for_permissions_and_becomes_ready() {
    val graph = app.graph
    val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle()).start().resume()
    val request = shadowOf(controller.get()).lastRequestedPermission
    assertNotNull("a restored activity in a new process must ask for permissions", request)
    controller.get().onRequestPermissionsResult(
      request.requestCode,
      request.requestedPermissions,
      IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED },
    )
    waitUntil { graph.isReady.value }
    controller.pause().stop().destroy()
  }

  @Test
  fun a_recreated_activity_does_not_ask_again_in_the_same_process() {
    ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
      scenario.onActivity { assertNotNull(shadowOf(it).lastRequestedPermission) }
      scenario.recreate()
      scenario.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
    }
  }
}
