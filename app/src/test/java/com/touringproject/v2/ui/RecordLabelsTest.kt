package com.touringproject.v2.ui

import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.records.RideListItem
import com.touringproject.v2.records.SaveState
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.pause
import com.touringproject.v2.ride.resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RecordLabelsTest {
  private val zone = ZoneId.of("Asia/Tokyo")

  private fun ms(day: Int, hour: Int, minute: Int, month: Int = 10) = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

  private fun ask(id: String, at: Long, transcript: String? = "右手の山は？", newConversation: Boolean = false, failure: String? = null) = AskRecord(
    askId = id, askedAt = at, answeredAt = at + 5_000, transcript = transcript, answer = "金時山です。",
    latitude = 35.2, longitude = 139.0, address = "神奈川県足柄下郡箱根町仙石原", headingDegrees = 45.0, input = "voice",
    conversationId = "c1", newConversation = newConversation,
    outcome = if (failure == null) AskOutcome.ANSWERED else AskOutcome.FAILED, failure = failure, rideId = "r1",
  )

  // 16 Oct 08:58 – 09:44, paused until 10:02, then 10:02 – 10:46.
  private val ride = newRide("r1", "箱根", ms(16, 8, 58), 7, 24 * HOUR_MS)
    .pause(ms(16, 9, 44)).resume(ms(16, 10, 2), 7, 24 * HOUR_MS).end(ms(16, 10, 46), EndReason.MANUAL, ms(16, 10, 46))
    .copy(distanceMeters = 52_800.0)

  @Test
  fun a_ride_row_says_when_how_far_and_how_it_was_kept() {
    val row = rideRow(RideListItem(ride, 4, SaveState.SAVED), ms(17, 0, 0), zone)
    assertEquals("10月16日（金） · 08:58〜10:46", row.date)
    assertEquals("52.8 km · 1:30 · 質問 4 · ⏸ 1", row.stats)
    assertEquals("保存済み", row.state)
  }

  @Test
  fun a_ride_over_nights_shows_its_days_and_one_still_recording_has_no_end() {
    val overnight = newRide("r2", "伊豆", ms(16, 9, 0), 7, 48 * HOUR_MS).end(ms(18, 15, 0), EndReason.MANUAL, ms(18, 15, 0))
    assertEquals("10月16日〜18日（2泊）", rideDateLabel(overnight, 0, zone))
    val acrossMonths = newRide("r3", "信州", ms(31, 9, 0), 7, 48 * HOUR_MS).end(ms(1, 15, 0, month = 11), EndReason.MANUAL, ms(1, 15, 0, month = 11))
    assertEquals("10月31日〜11月1日（1泊）", rideDateLabel(acrossMonths, 0, zone))
    val recording = newRide("r4", "富士", ms(16, 9, 0), 7, 24 * HOUR_MS)
    assertEquals("09:00〜", rideSpanLabel(recording, zone))
    assertEquals("● 記録中", rideRow(RideListItem(recording, 0, SaveState.RECORDING), ms(16, 10, 0), zone).state)
  }

  @Test
  fun rides_are_grouped_under_the_month_they_started() {
    val september = newRide("r0", "秩父", ms(20, 9, 0, month = 9), 7, 24 * HOUR_MS).end(ms(20, 12, 0, month = 9), EndReason.MANUAL, ms(20, 12, 0, month = 9))
    val sections = rideSections(listOf(ride, september).map { RideListItem(it, 0, SaveState.UNSENT) }, 0, zone)
    assertEquals(listOf("2026年10月", "2026年9月"), sections.map { it.month })
    assertEquals("未送信あり", sections[0].rows.single().state)
  }

  @Test
  fun the_timeline_puts_asks_pauses_and_new_conversations_in_time_order() {
    val asks = listOf(
      ask("a2", ms(16, 10, 30), newConversation = true),
      ask("a1", ms(16, 9, 41), newConversation = true),
      ask("a3", ms(16, 10, 40), transcript = null, failure = "no_speech"),
    )
    assertEquals(
      listOf(
        TimelineRow.Ask("a1", 1, "09:41", "足柄下郡箱根町仙石原", "右手の山は？", false),
        TimelineRow.Pause("⏸ 一時停止 09:44〜10:02（18分）"),
        TimelineRow.NewConversation,
        TimelineRow.Ask("a2", 2, "10:30", "足柄下郡箱根町仙石原", "右手の山は？", false),
        TimelineRow.Ask("a3", 3, "10:40", "足柄下郡箱根町仙石原", "（聞き取れませんでした）", true),
      ),
      rideTimeline(ride, asks, zone),
    )
  }

  @Test
  fun a_ride_over_a_night_gets_a_heading_per_day_and_a_paused_one_ends_with_its_pause() {
    val overnight = newRide("r2", "伊豆", ms(16, 15, 0), 7, 48 * HOUR_MS).pause(ms(16, 21, 10)).resume(ms(17, 8, 30), 7, 48 * HOUR_MS).pause(ms(17, 12, 0))
    assertEquals(
      listOf(
        TimelineRow.Day("10月16日（金）"),
        TimelineRow.Ask("a1", 1, "16:00", "足柄下郡箱根町仙石原", "右手の山は？", false),
        TimelineRow.Pause("⏸ 一時停止 21:10〜10月17日 08:30"),
        TimelineRow.Day("10月17日（土）"),
        TimelineRow.Ask("a2", 2, "09:00", "足柄下郡箱根町仙石原", "右手の山は？", false),
        TimelineRow.Pause("⏸ 一時停止中 12:00〜"),
      ),
      rideTimeline(overnight, listOf(ask("a1", ms(16, 16, 0)), ask("a2", ms(17, 9, 0))), zone),
    )
  }

  @Test
  fun pins_are_numbered_like_the_timeline_and_an_ask_without_a_place_has_none() {
    val asks = listOf(ask("a2", ms(16, 10, 30)), ask("a1", ms(16, 9, 41)).copy(latitude = null, longitude = null))
    assertEquals(listOf("a2" to 2), askPins(asks).map { it.askId to it.number })
  }

  @Test
  fun the_direction_names_the_way_and_both_hands_and_no_heading_means_stopped() {
    assertEquals("北東へ走行中（右手 南東・左手 北西）", directionLabel(45.0))
    assertEquals("北へ走行中（右手 東・左手 西）", directionLabel(350.0))
    assertEquals("停車中", directionLabel(null))
  }

  @Test
  fun the_detail_says_its_place_among_the_rides_asks_and_why_an_ask_failed() {
    val asks = listOf(ask("a1", ms(16, 9, 41)), ask("a2", ms(16, 10, 30), transcript = null, failure = "timeout"), ask("a3", ms(16, 10, 40)))
    val detail = askDetail(asks, "a2", zone)!!
    assertEquals("会話 2 / 3", detail.title)
    assertEquals("10月16日（金） 10:30", detail.time)
    assertEquals("神奈川県足柄下郡箱根町仙石原", detail.place)
    assertEquals("🎤 声で質問", detail.input)
    assertNull(detail.question)
    assertEquals("時間内に答えが届きませんでした", detail.failure)
    assertEquals("a1" to "a3", detail.previous to detail.next)
    assertNull(askDetail(asks, "a1", zone)!!.previous)
    assertNull(askDetail(asks, "a9", zone))
  }

  @Test
  fun a_pin_card_shows_the_number_the_time_and_the_short_place() {
    val card = pinCard(listOf(ask("a1", ms(16, 9, 41))), "a1", zone)!!
    assertEquals("1 · 09:41 · 足柄下郡箱根町仙石原", card.heading)
    assertEquals("金時山です。", card.answer)
  }
}
