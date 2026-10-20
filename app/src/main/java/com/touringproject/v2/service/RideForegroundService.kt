package com.touringproject.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.touringproject.v2.MainActivity
import com.touringproject.v2.TouringApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val HEARTBEAT_MS = 60_000L

fun serviceTypes(uses: Set<ServiceUse>): Int = uses.fold(0) { types, use ->
  types or when (use) {
    ServiceUse.LOCATION -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    ServiceUse.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    ServiceUse.MEDIA_PLAYBACK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
  }
}

/**
 * Keeps the process alive while a ride records (taking in its route, every minute marking it alive and checking its
 * deadline) and while an answer is awaited and read out (adr/0004, adr/0005, slice 2 spec §4.2). Its notification
 * doubles as the notice that a ride is recording (EX-03).
 */
class RideForegroundService : Service(), RunningService {
  private val graph get() = (application as TouringApp).graph
  private lateinit var scope: CoroutineScope
  private var foreground = false
  private lateinit var recording: RecordingWatch

  private val shutdown = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val pending = goAsync()
      scope.launch {
        try {
          graph.rides.shutdown()
        } finally {
          pending.finish()
        }
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    scope = MainScope()
    val rides = graph.rides
    recording = RecordingWatch(
      graph.routeSource,
      onFix = { fix -> scope.launch { rides.onFix(fix) } },
      startHeartbeat = {
        scope.launch {
          while (true) {
            delay(HEARTBEAT_MS)
            rides.heartbeat()
          }
        }
      },
    ) { message, e -> Log.w("trv2.route", message, e) }
    ContextCompat.registerReceiver(this, shutdown, IntentFilter(Intent.ACTION_SHUTDOWN), ContextCompat.RECEIVER_NOT_EXPORTED)
    graph.serviceCoordinator.attach(this)
  }

  // Also reached when the system restarts the service after the process died (START_STICKY): the ride left recording
  // is settled first (RideController.load), then the service takes on whatever is still wanted.
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    scope.launch {
      graph.rides.load()
      val uses = graph.serviceCoordinator.uses
      try {
        apply(uses)
      } catch (e: RuntimeException) {
        // Android 14+ refuses a while-in-use type started from the background, or without its permission.
        Log.w("trv2.ride", "startForeground failed uses=$uses", e)
        val fallback = uses - setOf(ServiceUse.MICROPHONE, ServiceUse.MEDIA_PLAYBACK)
        if (fallback != uses && fallback.isNotEmpty()) runCatching { apply(fallback) }.onFailure { stop() } else stop()
      }
    }
    return START_STICKY
  }

  override fun apply(uses: Set<ServiceUse>) {
    if (uses.isEmpty()) {
      stop()
      return
    }
    startForeground(NOTIFICATION_ID, notification(uses), serviceTypes(uses))
    foreground = true
    if (ServiceUse.LOCATION in uses) recording.start() else recording.stop()
  }

  private fun stop() {
    // From here on a new request starts the service again instead of reaching this stopping one.
    graph.serviceCoordinator.detach(this)
    recording.stop()
    // A service started with startForegroundService must reach the foreground once, even to stop at once.
    if (!foreground) runCatching { startForeground(NOTIFICATION_ID, notification(emptySet()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
    stopForeground(STOP_FOREGROUND_REMOVE)
    foreground = false
    stopSelf()
  }

  private fun notification(uses: Set<ServiceUse>): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "走行の記録と応答", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("Touring V2")
      .setContentText(if (ServiceUse.LOCATION in uses) "走行を記録中" else "応答中")
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(open)
      .setOngoing(true)
      .build()
  }

  override fun onDestroy() {
    recording.stop()
    unregisterReceiver(shutdown)
    graph.serviceCoordinator.detach(this)
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    const val CHANNEL_ID = "trv2_answer"
    const val NOTIFICATION_ID = 1
  }
}

class ContextServiceLauncher(private val context: Context) : ServiceLauncher {
  override fun start() {
    context.startForegroundService(Intent(context, RideForegroundService::class.java))
  }

  override fun stop() {
    context.stopService(Intent(context, RideForegroundService::class.java))
  }
}
