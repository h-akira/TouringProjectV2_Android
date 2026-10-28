package com.touringproject.v2.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.TouringApp
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.newRide
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class RideForegroundServiceTest {
  private val app = ApplicationProvider.getApplicationContext<TouringApp>()

  // Room works on its own threads and resumes on the main looper, so the main looper is turned until the result shows.
  private fun waitUntil(condition: () -> Boolean) {
    repeat(200) {
      shadowOf(Looper.getMainLooper()).idle()
      if (condition()) return
      Thread.sleep(10)
    }
    throw AssertionError("condition never became true")
  }

  private fun startedService() = Robolectric.buildService(RideForegroundService::class.java).create().startCommand(0, 1)

  @Test
  fun the_launcher_starts_and_stops_the_service() {
    val launcher = ContextServiceLauncher(app)
    launcher.start()
    assertEquals(RideForegroundService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    launcher.stop()
    assertEquals(RideForegroundService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
  }

  @Test
  fun the_service_takes_the_types_it_is_used_for() {
    val controller = startedService()
    val service = controller.get()
    service.apply(setOf(ServiceUse.LOCATION))
    assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
    assertEquals("走行を記録中", shadowOf(shadowOf(service).lastForegroundNotification).contentText)
    service.apply(ServiceUse.entries.toSet())
    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      service.foregroundServiceType,
    )
    service.apply(setOf(ServiceUse.MICROPHONE, ServiceUse.MEDIA_PLAYBACK))
    assertEquals("応答中", shadowOf(shadowOf(service).lastForegroundNotification).contentText)
  }

  @Test
  fun started_with_nothing_to_do_it_stops_itself() {
    val controller = startedService()
    waitUntil { shadowOf(controller.get()).isStoppedBySelf }
  }

  @Test
  fun the_ask_keep_alive_reaches_the_running_service() {
    val controller = startedService()
    val coordinator = app.graph.serviceCoordinator
    coordinator.keepAlive.start()
    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      controller.get().foregroundServiceType,
    )
    coordinator.keepAlive.stop()
    assertTrue(shadowOf(controller.get()).isStoppedBySelf)
  }

  @Test
  fun a_shutdown_ends_the_recording_ride() {
    val rides = app.graph.rides
    runBlocking { rides.load() }
    runBlocking { rides.start("箱根") }
    startedService()
    app.sendBroadcast(Intent(Intent.ACTION_SHUTDOWN))
    waitUntil { rides.active.value == null }
  }

  @Test
  fun while_recording_the_ride_is_marked_alive_every_minute() {
    val rides = app.graph.rides
    runBlocking { rides.load() }
    runBlocking { rides.start("箱根") }
    val controller = startedService()
    waitUntil { controller.get().foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION }
    val before = runBlocking { app.graph.rideStore.active() }!!.lastAliveAt!!
    Thread.sleep(5)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HEARTBEAT_MS))
    waitUntil { runBlocking { app.graph.rideStore.active() }!!.lastAliveAt!! > before }
  }

  @Test
  fun the_deadline_alarm_is_set_and_cancelled() {
    val alarm = AlarmDeadline(app)
    val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
    alarm.set("r1", 123_456)
    assertEquals(123_456L, alarms.peekNextScheduledAlarm()?.triggerAtTime)
    alarm.cancel()
    assertNull(alarms.peekNextScheduledAlarm())
  }

  @Test
  fun the_deadline_receiver_ends_an_overdue_ride_and_posts_the_notice() {
    // Saved before the app's own load runs, as if the process had died while recording.
    runBlocking { app.graph.rideStore.save(newRide("r1", "箱根", 0, null, 1_000)) }
    app.sendBroadcast(Intent(app, DeadlineReceiver::class.java))
    // The notice comes after the ride is saved as ended, so it is waited for too (Android #59).
    val notices = shadowOf(app.getSystemService(NotificationManager::class.java))
    waitUntil {
      app.graph.rides.active.value == null && runBlocking { app.graph.rideStore.active() } == null && notices.allNotifications.isNotEmpty()
    }
    val posted = notices.allNotifications.single()
    assertEquals("走行を自動で終了しました", shadowOf(posted).contentTitle)
  }

  @Test
  fun the_auto_end_notice_names_the_ride() {
    val ride = newRide("r1", "箱根", 0, 7, 24 * HOUR_MS).copy(endReason = EndReason.AUTO)
    RideNotices(app).autoEnded(ride)
    val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
    assertEquals("走行を自動で終了しました", shadowOf(posted).contentTitle)
    assertNotNull(shadowOf(posted).contentText?.toString()?.takeIf { "「箱根」" in it })
  }
}
