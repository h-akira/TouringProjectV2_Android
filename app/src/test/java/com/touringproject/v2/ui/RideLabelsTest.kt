package com.touringproject.v2.ui

import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.Pauses
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.pause
import com.touringproject.v2.ride.resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val TOKYO = ZoneId.of("Asia/Tokyo")
private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, TOKYO).toInstant().toEpochMilli()

class RideLabelsTest {
  @Test
  fun elapsed_and_distance_read_like_the_mock() {
    assertEquals("1:26", elapsedLabel(86 * 60_000L + 59_000))
    assertEquals("0:00", elapsedLabel(0))
    assertEquals("52.8 km", distanceLabel(52_800.0))
  }

  @Test
  fun the_period_shows_dates_only_when_the_ride_runs_over_midnight() {
    assertEquals("08:58〜10:46", periodLabel(at(17, 8, 58), at(17, 10, 46), TOKYO))
    assertEquals("10月17日 18:00〜10月18日 09:30", periodLabel(at(17, 18, 0), at(18, 9, 30), TOKYO))
  }

  @Test
  fun pauses_are_mentioned_only_when_there_were_any() {
    assertNull(pausesLabel(Pauses(0, 0)))
    assertEquals("一時停止 1回（18分）は走行時間に含めません", pausesLabel(Pauses(1, 18 * 60_000L)))
  }

  @Test
  fun the_end_summary_of_a_paused_ride_ends_at_its_pause() {
    val ride = newRide("r1", "箱根", at(17, 8, 58), 7, 24 * HOUR_MS)
      .pause(at(17, 9, 30)).resume(at(17, 9, 48), 7, 24 * HOUR_MS).pause(at(17, 10, 46))
    val summary = endSummary(ride.copy(distanceMeters = 62_400.0), at(17, 11, 30), TOKYO, questions = 4)
    assertEquals(EndSummaryUi("1:30", "62.4 km", "4", "08:58〜10:46 · 一時停止 1回（18分）は走行時間に含めません"), summary)
  }

  @Test
  fun the_card_says_whether_the_ride_is_paused() {
    val ride = newRide("r1", "箱根", 0, 7, 24 * HOUR_MS)
    assertEquals(RideCardUi("箱根", false, "0:01", "0.0 km", "3"), rideCard(ride, 60_000, questions = 3))
    assertEquals(true, rideCard(ride.pause(60_000), 120_000, questions = 0).paused)
  }
}
