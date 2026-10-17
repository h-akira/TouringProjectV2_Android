package com.touringproject.v2.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

fun Location.toFix() = Fix(latitude, longitude, time, if (hasAccuracy()) accuracy.toDouble() else null)

// Without the location permission both calls throw SecurityException; PositionTracker then simply has no fixes.
@SuppressLint("MissingPermission")
class FusedPositionSource(context: Context) : PositionSource {
  private val client = LocationServices.getFusedLocationProviderClient(context)

  override fun now() = System.currentTimeMillis()

  override fun lastKnown(onFix: (Fix) -> Unit) {
    client.lastLocation.addOnSuccessListener { it?.let { location -> onFix(location.toFix()) } }
  }

  override fun watch(onFix: (Fix) -> Unit): Subscription {
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000).setMinUpdateIntervalMillis(1_000).build()
    val callback = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) = result.locations.forEach { onFix(it.toFix()) }
    }
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    return Subscription { client.removeLocationUpdates(callback) }
  }
}
