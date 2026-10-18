package com.touringproject.v2.ui

import com.touringproject.v2.ride.Pauses
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.pauses
import com.touringproject.v2.ride.recordedMs
import com.touringproject.v2.ride.startedAt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// How a ride is described on SC-02 and SC-04 (the mock's trip card and end sheet).

/** Hours and minutes, e.g. 1:26. */
fun elapsedLabel(ms: Long): String {
  val minutes = ms / 60_000
  return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60)
}

fun distanceLabel(meters: Double): String = String.format(Locale.ROOT, "%.1f km", meters / 1000)

/** 08:58〜10:46 within a day; with the dates when the ride runs over midnight. */
fun periodLabel(start: Long, end: Long, zone: ZoneId): String {
  val from = Instant.ofEpochMilli(start).atZone(zone)
  val to = Instant.ofEpochMilli(end).atZone(zone)
  val time = DateTimeFormatter.ofPattern("HH:mm")
  if (from.toLocalDate() == to.toLocalDate()) return "${time.format(from)}〜${time.format(to)}"
  val day = DateTimeFormatter.ofPattern("M月d日 HH:mm")
  return "${day.format(from)}〜${day.format(to)}"
}

fun pausesLabel(pauses: Pauses): String? =
  if (pauses.count == 0) null else "一時停止 ${pauses.count}回（${pauses.totalMs / 60_000}分）は走行時間に含めません"

data class RideCardUi(val name: String, val paused: Boolean, val elapsed: String, val distance: String)

fun rideCard(ride: Ride, now: Long) = RideCardUi(ride.name, ride.status == RideStatus.PAUSED, elapsedLabel(ride.recordedMs(now)), distanceLabel(ride.distanceMeters))

data class EndSummaryUi(val elapsed: String, val distance: String, val note: String)

/** SC-04's summary as if the ride ended now. */
fun endSummary(ride: Ride, now: Long, zone: ZoneId): EndSummaryUi {
  val end = if (ride.status == RideStatus.PAUSED) ride.segments.last().endedAt ?: now else now
  val note = listOfNotNull(periodLabel(ride.startedAt(), end, zone), pausesLabel(ride.pauses())).joinToString(" · ")
  return EndSummaryUi(elapsedLabel(ride.recordedMs(now)), distanceLabel(ride.distanceMeters), note)
}
