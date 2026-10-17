package com.touringproject.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.touringproject.v2.ask.KeepAlive

// Keeps the process alive while an answer is awaited and read out in the background (adr/0004, adr/0005).
class RideForegroundService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "応答", NotificationManager.IMPORTANCE_LOW))
    val notification = Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("Touring V2")
      .setContentText("応答中")
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setOngoing(true)
      .build()
    try {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      )
    } catch (e: RuntimeException) {
      // Android 14+ throws when the microphone permission is missing; the ask then fails on its own instead of crashing.
      Log.w("trv2.ride", "startForeground failed", e)
      stopSelf()
    }
    return START_NOT_STICKY
  }

  companion object {
    const val CHANNEL_ID = "trv2_answer"
    const val NOTIFICATION_ID = 1
  }
}

class ServiceKeepAlive(private val context: Context) : KeepAlive {
  override fun start() {
    context.startForegroundService(Intent(context, RideForegroundService::class.java))
  }

  override fun stop() {
    context.stopService(Intent(context, RideForegroundService::class.java))
  }
}
