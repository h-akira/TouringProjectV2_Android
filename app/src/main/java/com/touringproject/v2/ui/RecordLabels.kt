package com.touringproject.v2.ui

import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.map.LatLon
import com.touringproject.v2.map.MapPin
import com.touringproject.v2.records.RideListItem
import com.touringproject.v2.records.SaveState
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.pauses
import com.touringproject.v2.ride.recordedMs
import com.touringproject.v2.ride.startedAt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// How SC-05, SC-06 and SC-07 describe rides and asks (the mock's records screens, slice 2 spec §7.4–§7.6).

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private const val WEEKDAYS = "月火水木金土日"

private fun at(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

/** 10月16日（金）; without the weekday, 10月16日. */
fun dayLabel(date: LocalDate, weekday: Boolean = true): String {
  val day = "${date.monthValue}月${date.dayOfMonth}日"
  return if (weekday) "$day（${WEEKDAYS[date.dayOfWeek.value - 1]}）" else day
}

private fun Ride.lastMs(now: Long) = segments.last().endedAt ?: now

/** 10月16日（金）; over nights, 10月16日〜18日（2泊）, or 10月31日〜11月1日（1泊） across a month. */
fun rideDateLabel(ride: Ride, now: Long, zone: ZoneId): String {
  val from = at(ride.startedAt(), zone).toLocalDate()
  val to = at(ride.lastMs(now), zone).toLocalDate()
  if (from == to) return dayLabel(from)
  val nights = ChronoUnit.DAYS.between(from, to)
  val end = if (from.monthValue == to.monthValue) "${to.dayOfMonth}日" else dayLabel(to, weekday = false)
  return "${dayLabel(from, weekday = false)}〜$end（${nights}泊）"
}

/** 08:58〜16:20; a ride still recording or paused has no end yet: 08:58〜. */
fun rideSpanLabel(ride: Ride, zone: ZoneId): String {
  val start = TIME.format(at(ride.startedAt(), zone))
  val end = if (ride.status == RideStatus.ENDED) ride.segments.last().endedAt?.let { TIME.format(at(it, zone)) } ?: "" else ""
  return "$start〜$end"
}

fun saveLabel(item: RideListItem): String = when (item.saveState) {
  SaveState.RECORDING -> if (item.ride.status == RideStatus.PAUSED) "⏸ 一時停止中" else "● 記録中"
  SaveState.UNSENT -> "未送信あり"
  SaveState.SAVED -> "保存済み"
}

data class RideRowUi(val id: String, val name: String, val date: String, val stats: String, val state: String, val saved: Boolean)

/** One ride of SC-05: 52.8 km · 1:26 · 質問 4 · ⏸ 1 (the pauses only when there were some). */
fun rideRow(item: RideListItem, now: Long, zone: ZoneId): RideRowUi {
  val ride = item.ride
  val pauses = ride.pauses().count
  val stats = listOfNotNull(
    distanceLabel(ride.distanceMeters),
    elapsedLabel(ride.recordedMs(now)),
    "質問 ${item.questions}",
    if (pauses > 0) "⏸ $pauses" else null,
  ).joinToString(" · ")
  return RideRowUi(
    ride.id, ride.name, "${rideDateLabel(ride, now, zone)} · ${rideSpanLabel(ride, zone)}", stats, saveLabel(item),
    item.saveState == SaveState.SAVED,
  )
}

data class RideSection(val month: String, val rows: List<RideRowUi>)

/** The ordered rides under a heading per month of their start: 2026年10月. */
fun rideSections(items: List<RideListItem>, now: Long, zone: ZoneId): List<RideSection> {
  val sections = mutableListOf<RideSection>()
  items.forEach { item ->
    val start = at(item.ride.startedAt(), zone)
    val month = "${start.year}年${start.monthValue}月"
    val row = rideRow(item, now, zone)
    if (sections.lastOrNull()?.month == month) sections[sections.lastIndex] = sections.last().let { it.copy(rows = it.rows + row) }
    else sections += RideSection(month, listOf(row))
  }
  return sections
}

/** SC-06's summary. pauses is null without one; autoEnded says the longest recording time ended the ride. */
data class RideSummaryUi(
  val date: String,
  val span: String,
  val elapsed: String,
  val distance: String,
  val questions: String,
  val pauses: String?,
  val autoEnded: Boolean,
)

fun rideSummary(ride: Ride, questions: Int, now: Long, zone: ZoneId) = RideSummaryUi(
  date = rideDateLabel(ride, now, zone),
  span = rideSpanLabel(ride, zone),
  elapsed = elapsedLabel(ride.recordedMs(now)),
  distance = distanceLabel(ride.distanceMeters),
  questions = questions.toString(),
  pauses = pausesLabel(ride.pauses()),
  autoEnded = ride.endReason == EndReason.AUTO,
)

/** The asks in the order they were asked, numbered from 1: the numbers of the pins and the timeline. */
fun askNumbers(asks: List<AskRecord>): Map<String, Int> =
  asks.sortedBy { it.askedAt }.mapIndexed { i, ask -> ask.askId to i + 1 }.toMap()

/** The ride's asks that know where they were asked, as numbered pins. */
fun askPins(asks: List<AskRecord>): List<MapPin> {
  val numbers = askNumbers(asks)
  return asks.sortedBy { it.askedAt }.mapNotNull { ask ->
    val lat = ask.latitude ?: return@mapNotNull null
    val lon = ask.longitude ?: return@mapNotNull null
    MapPin(ask.askId, numbers.getValue(ask.askId), LatLon(lat, lon))
  }
}

/** The mock's short place: the address without its prefecture. */
fun shortPlace(address: String?): String = address?.replace(Regex("^(東京都|.+?[県府道])"), "")?.ifBlank { null } ?: "場所不明"

/** What a list shows of an ask: what was heard, or why nothing was. */
fun askText(ask: AskRecord): String = ask.transcript?.takeIf { it.isNotBlank() } ?: when {
  ask.outcome == AskOutcome.ANSWERED -> "（聞き取った文がありません）"
  ask.failure == "no_speech" -> "（聞き取れませんでした）"
  ask.failure == "timeout" -> "（時間内に答えが届きませんでした）"
  else -> "（うまく答えられませんでした）"
}

sealed interface TimelineRow {
  data class Day(val label: String) : TimelineRow
  data class Ask(val askId: String, val number: Int, val time: String, val place: String, val text: String, val failed: Boolean) : TimelineRow
  data class Pause(val label: String) : TimelineRow
  data object NewConversation : TimelineRow
}

/** ⏸ 一時停止 09:44〜10:02（18分）; over midnight, ⏸ 一時停止 21:10〜10月17日 08:30. */
fun pauseLabel(from: Long, to: Long, zone: ZoneId): String {
  val a = at(from, zone)
  val b = at(to, zone)
  if (a.toLocalDate() == b.toLocalDate()) return "⏸ 一時停止 ${TIME.format(a)}〜${TIME.format(b)}（${(to - from) / 60_000}分）"
  return "⏸ 一時停止 ${TIME.format(a)}〜${dayLabel(b.toLocalDate(), weekday = false)} ${TIME.format(b)}"
}

private sealed interface Event {
  val t: Long
  data class AskAt(override val t: Long, val ask: AskRecord) : Event
  data class PauseAt(override val t: Long, val until: Long?) : Event
}

/**
 * SC-06's timeline: the ride's asks, its pauses and the breaks between conversations, in time order. A ride over
 * more than one day gets a heading for each day (slice 2 spec §7.5). A paused ride ends with its pause so far.
 */
fun rideTimeline(ride: Ride, asks: List<AskRecord>, zone: ZoneId): List<TimelineRow> {
  val numbers = askNumbers(asks)
  val pauses = ride.segments.zipWithNext { a, b -> Event.PauseAt(a.endedAt ?: b.startedAt, b.startedAt) }
  val open = if (ride.status == RideStatus.PAUSED) listOfNotNull(ride.segments.last().endedAt?.let { Event.PauseAt(it, null) }) else emptyList()
  // An ask comes before a pause that starts at the same moment.
  val events = (asks.map { Event.AskAt(it.askedAt, it) } + pauses + open).sortedWith(compareBy<Event> { it.t }.thenBy { if (it is Event.AskAt) 0 else 1 })
  val days = (events.map { it.t } + ride.startedAt()).map { at(it, zone).toLocalDate() }.toSet()
  val rows = mutableListOf<TimelineRow>()
  var day: LocalDate? = null
  var asked = false
  events.forEach { event ->
    val date = at(event.t, zone).toLocalDate()
    if (days.size > 1 && date != day) rows += TimelineRow.Day(dayLabel(date))
    day = date
    when (event) {
      is Event.AskAt -> {
        val ask = event.ask
        if (ask.newConversation && asked) rows += TimelineRow.NewConversation
        asked = true
        rows += TimelineRow.Ask(
          ask.askId, numbers.getValue(ask.askId), TIME.format(at(ask.askedAt, zone)), shortPlace(ask.address), askText(ask),
          ask.outcome == AskOutcome.FAILED,
        )
      }
      is Event.PauseAt -> rows += TimelineRow.Pause(
        event.until?.let { pauseLabel(event.t, it, zone) } ?: "⏸ 一時停止中 ${TIME.format(at(event.t, zone))}〜",
      )
    }
  }
  return rows
}

private val COMPASS = listOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")

/** The nearest of the eight points of the compass. */
fun compass(degrees: Double): String = COMPASS[((((degrees % 360) + 360) % 360 + 22.5) / 45).toInt() % 8]

/** SC-07: 北東へ走行中（右手 南東・左手 北西）; without a heading, 停車中. */
fun directionLabel(heading: Double?): String =
  heading?.let { "${compass(it)}へ走行中（右手 ${compass(it + 90)}・左手 ${compass(it - 90)}）" } ?: "停車中"

/** Why an ask got no answer (SC-07). */
fun failureLabel(failure: String?): String = when (failure) {
  "no_speech" -> "声が聞き取れませんでした"
  "transcribe_failed" -> "声を文字にできませんでした"
  "timeout" -> "時間内に答えが届きませんでした"
  else -> "AI が答えられませんでした"
}

/** SC-07. question is null when nothing was heard; failure is set for an ask that got no answer. */
data class AskDetailUi(
  val title: String,
  val time: String,
  val place: String,
  val direction: String,
  val input: String,
  val question: String?,
  val answer: String?,
  val failure: String?,
  val previous: String?,
  val next: String?,
)

/** askId's place among the asks it was opened from (the ride's): 会話 3 / 5, and the asks before and after it. */
fun askDetail(asks: List<AskRecord>, askId: String, zone: ZoneId): AskDetailUi? {
  val ordered = asks.sortedBy { it.askedAt }
  val i = ordered.indexOfFirst { it.askId == askId }
  if (i < 0) return null
  val ask = ordered[i]
  val time = at(ask.askedAt, zone)
  return AskDetailUi(
    title = "会話 ${i + 1} / ${ordered.size}",
    time = "${dayLabel(time.toLocalDate())} ${TIME.format(time)}",
    place = ask.address?.ifBlank { null } ?: "場所不明",
    direction = directionLabel(ask.headingDegrees),
    input = if (ask.input == "text") "⌨ 文字で質問" else "🎤 声で質問",
    question = ask.transcript?.ifBlank { null },
    answer = ask.answer?.ifBlank { null },
    failure = if (ask.outcome == AskOutcome.FAILED) failureLabel(ask.failure) else null,
    previous = ordered.getOrNull(i - 1)?.askId,
    next = ordered.getOrNull(i + 1)?.askId,
  )
}

/** The card of a tapped pin: 3 · 09:41 · 仙石原, then the question and the start of the answer. */
data class PinCardUi(val askId: String, val heading: String, val question: String, val answer: String?)

fun pinCard(asks: List<AskRecord>, askId: String, zone: ZoneId): PinCardUi? {
  val ask = asks.firstOrNull { it.askId == askId } ?: return null
  val number = askNumbers(asks).getValue(askId)
  return PinCardUi(askId, "$number · ${TIME.format(at(ask.askedAt, zone))} · ${shortPlace(ask.address)}", askText(ask), ask.answer?.ifBlank { null })
}
