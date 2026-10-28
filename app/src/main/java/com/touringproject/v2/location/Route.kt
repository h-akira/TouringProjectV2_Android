package com.touringproject.v2.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// The route of a ride (slice 2 spec §4.3). The values are reviewed after real rides; the rider does not change them.
const val ROUTE_INTERVAL_MS = 5_000L
const val ROUTE_MIN_DISTANCE_M = 10f
const val ROUTE_MAX_ACCURACY_M = 50.0

/** US-C-02.005: a point less accurate than 50 m is dropped. A point without an accuracy is kept. */
fun acceptRoutePoint(fix: Fix): Boolean = fix.accuracy == null || fix.accuracy <= ROUTE_MAX_ACCURACY_M

/**
 * Whether the fix was made after a stretch of route began: Fused may hand over a cached fix from before when a watch
 * starts. Compared on the time since boot (sinceElapsed) when both sides tell it, so a clock set forward or back
 * drops nothing (Android #17, #24); otherwise the fix's time against the clock's (sinceClock).
 */
fun Fix.isSince(sinceElapsed: Long?, sinceClock: Long): Boolean =
  if (elapsedMs != null && sinceElapsed != null) elapsedMs >= sinceElapsed else timestamp >= sinceClock

fun interface RouteSource {
  fun watch(onFix: (Fix) -> Unit): Subscription
}

// A separate request from the ask's 1 s one (PositionTracker): Fused serves both, and the two never mix.
// Without the location permission the request throws SecurityException; the caller then records no points.
@SuppressLint("MissingPermission")
class FusedRouteSource(context: Context) : RouteSource {
  private val client = LocationServices.getFusedLocationProviderClient(context)

  override fun watch(onFix: (Fix) -> Unit): Subscription {
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ROUTE_INTERVAL_MS)
      .setMinUpdateDistanceMeters(ROUTE_MIN_DISTANCE_M)
      .build()
    val callback = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) = result.locations.forEach { onFix(it.toFix()) }
    }
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    return Subscription { client.removeLocationUpdates(callback) }
  }
}
