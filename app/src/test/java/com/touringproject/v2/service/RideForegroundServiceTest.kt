package com.touringproject.v2.service

import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class RideForegroundServiceTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun keep_alive_starts_and_stops_the_service() {
    val keepAlive = ServiceKeepAlive(app)
    keepAlive.start()
    assertEquals(RideForegroundService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    keepAlive.stop()
    assertEquals(RideForegroundService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
  }

  @Test
  fun the_service_runs_in_the_foreground_for_the_microphone_and_playback() {
    val controller = Robolectric.buildService(RideForegroundService::class.java).create().startCommand(0, 1)
    val shadow = shadowOf(controller.get())
    assertNotNull(shadow.lastForegroundNotification)
    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      controller.get().foregroundServiceType,
    )
  }
}
