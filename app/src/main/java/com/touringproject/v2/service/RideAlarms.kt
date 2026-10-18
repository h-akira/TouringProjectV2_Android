package com.touringproject.v2.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.touringproject.v2.MainActivity
import com.touringproject.v2.TouringApp
import com.touringproject.v2.ride.DeadlineAlarm
import com.touringproject.v2.ride.Ride
import kotlinx.coroutines.launch

// An inexact alarm needs no permission (an exact one does from Android 12, and is off by default from 14). It may
// come minutes late; the ride still ends at its deadline, so only the notice is late (slice 2 spec §4.4).
class AlarmDeadline(private val context: Context) : DeadlineAlarm {
  private val alarms = context.getSystemService(AlarmManager::class.java)

  private fun pending() = PendingIntent.getBroadcast(
    context, 0, Intent(context, DeadlineReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )

  override fun set(rideId: String, at: Long) = alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending())

  override fun cancel() = alarms.cancel(pending())
}

/** Starts the process if it has died; RideController.load then settles the ride before deadlineReached runs. */
class DeadlineReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val graph = (context.applicationContext as TouringApp).graph
    val pending = goAsync()
    graph.scope.launch {
      try {
        graph.rides.deadlineReached()
      } finally {
        pending.finish()
      }
    }
  }
}

class RideNotices(private val context: Context) {
  /** US-C-08.004. Skipped when the rider has turned notifications off. */
  fun autoEnded(ride: Ride) {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "走行の自動終了", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val notification = Notification.Builder(context, CHANNEL_ID)
      .setContentTitle("走行を自動で終了しました")
      .setContentText("「${ride.name}」は、続けて記録した時間が最長時間に達したので終了しました")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    manager.notify(NOTIFICATION_ID, notification)
  }

  companion object {
    const val CHANNEL_ID = "trv2_ride_notice"
    const val NOTIFICATION_ID = 2
  }
}
