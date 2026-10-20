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

/**
 * What Home says under the intercom line when no ask can start (slice 2 spec §4.7, §7.2): null while a ride records,
 * so the line says the intercom is ready.
 */
fun askHint(ride: Ride?): String? = when (ride?.status) {
  RideStatus.RECORDING -> null
  RideStatus.PAUSED -> "再開すると、インカムのボタンで話せます"
  else -> "走行を開始すると、インカムのボタンで話せます"
}

/** questions is how many of the ride's asks the device has kept (answered or failed; slice 2 spec §7.2). */
data class RideCardUi(val name: String, val paused: Boolean, val elapsed: String, val distance: String, val questions: String)

fun rideCard(ride: Ride, now: Long, questions: Int) =
  RideCardUi(ride.name, ride.status == RideStatus.PAUSED, elapsedLabel(ride.recordedMs(now)), distanceLabel(ride.distanceMeters), questions.toString())

data class EndSummaryUi(val elapsed: String, val distance: String, val questions: String, val note: String)

/** SC-04's summary as if the ride ended now (slice 2 spec §7.3). */
fun endSummary(ride: Ride, now: Long, zone: ZoneId, questions: Int): EndSummaryUi {
  val end = if (ride.status == RideStatus.PAUSED) ride.segments.last().endedAt ?: now else now
  val note = listOfNotNull(periodLabel(ride.startedAt(), end, zone), pausesLabel(ride.pauses())).joinToString(" · ")
  return EndSummaryUi(elapsedLabel(ride.recordedMs(now)), distanceLabel(ride.distanceMeters), questions.toString(), note)
}
