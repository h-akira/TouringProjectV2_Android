package com.touringproject.v2.ui

import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import com.touringproject.v2.map.LatLon
import com.touringproject.v2.map.MapPin
import com.touringproject.v2.map.PinKind
import com.touringproject.v2.records.NoteDraft
import com.touringproject.v2.records.NoteRecord
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

/** memo: the ride has a memo (US-D-04.005). */
data class RideRowUi(
  val id: String,
  val name: String,
  val date: String,
  val stats: String,
  val state: String,
  val saved: Boolean,
  val memo: Boolean = false,
)

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
    memo = ride.memo != null,
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

/**
 * SC-06's summary. pauses is null without one; autoEnded says the longest recording time ended the ride; notes counts
 * its point memos, null without one (the mock).
 */
data class RideSummaryUi(
  val date: String,
  val span: String,
  val elapsed: String,
  val distance: String,
  val questions: String,
  val pauses: String?,
  val autoEnded: Boolean,
  val notes: String? = null,
)

fun rideSummary(ride: Ride, questions: Int, now: Long, zone: ZoneId, notes: Int = 0) = RideSummaryUi(
  date = rideDateLabel(ride, now, zone),
  span = rideSpanLabel(ride, zone),
  elapsed = elapsedLabel(ride.recordedMs(now)),
  distance = distanceLabel(ride.distanceMeters),
  questions = questions.toString(),
  pauses = pausesLabel(ride.pauses()),
  autoEnded = ride.endReason == EndReason.AUTO,
  notes = if (notes > 0) "📝 地点メモ ${notes}件" else null,
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
    MapPin(ask.askId, numbers.getValue(ask.askId), LatLon(lat, lon), memo = ask.memo != null)
  }
}

/** The point memos in the order they were opened, numbered from 1 apart from the asks (US-D-08). */
fun noteNumbers(notes: List<NoteRecord>): Map<String, Int> =
  notes.sortedBy { it.at }.mapIndexed { i, note -> note.id to i + 1 }.toMap()

/** The ride's point memos that have a place, as green numbered pins. */
fun notePins(notes: List<NoteRecord>): List<MapPin> {
  val numbers = noteNumbers(notes)
  return notes.sortedBy { it.at }.mapNotNull { note ->
    val lat = note.latitude ?: return@mapNotNull null
    val lon = note.longitude ?: return@mapNotNull null
    MapPin(note.id, numbers.getValue(note.id), LatLon(lat, lon), kind = PinKind.NOTE)
  }
}

// The 43 prefectures named 〜県, by name: "two or three characters and 県" also matched an address like 西区県庁前 (Android #50).
private val KEN = listOf(
  "青森", "岩手", "宮城", "秋田", "山形", "福島", "茨城", "栃木", "群馬", "埼玉", "千葉", "神奈川", "新潟", "富山", "石川",
  "福井", "山梨", "長野", "岐阜", "静岡", "愛知", "三重", "滋賀", "兵庫", "奈良", "和歌山", "鳥取", "島根", "岡山", "広島",
  "山口", "徳島", "香川", "愛媛", "高知", "福岡", "佐賀", "長崎", "熊本", "大分", "宮崎", "鹿児島", "沖縄",
)
private val PREFECTURE = Regex("^(東京都|北海道|(京都|大阪)府|(${KEN.joinToString("|")})県)")

/** The mock's short place: the address without its prefecture; an address that starts otherwise is kept whole (Android #45). */
fun shortPlace(address: String?): String = address?.replace(PREFECTURE, "")?.ifBlank { null } ?: "場所不明"

/**
 * Where a point memo is, in short: its address once the Backend has settled it. Until it has gone, the address is
 * still to come; one that has gone without one could not be told (slice 3 spec §6.2).
 */
fun notePlace(note: NoteRecord): String = when {
  note.latitude == null -> "位置の記録なし"
  note.address != null -> shortPlace(note.address)
  note.sent -> "場所不明"
  else -> "住所は送信後"
}

/** What an unreached ask shows in place of its question, in lists and on pins (the mock's SC-07). */
const val UNREACHED_TEXT = "（通信できず、質問できなかった）"

/** What an unreached ask shows in place of its answer: nothing it said was kept (US-C-09.002). */
const val UNREACHED_ANSWER = "質問の内容は残していません"

/**
 * Where an ask was, in short. An unreached ask gets its address once it has been sent, as a point memo does (slice 4
 * spec §4.4); the others have theirs from the Backend.
 */
fun askPlace(ask: AskRecord): String = when {
  ask.outcome != AskOutcome.OFFLINE -> shortPlace(ask.address)
  ask.latitude == null -> "位置の記録なし"
  ask.address != null -> shortPlace(ask.address)
  ask.sent -> "場所不明"
  else -> "住所は送信後"
}

/** What a list shows of an ask: what was heard, or why nothing was. */
fun askText(ask: AskRecord): String = if (ask.outcome == AskOutcome.OFFLINE) UNREACHED_TEXT else ask.transcript?.takeIf { it.isNotBlank() } ?: when {
  ask.outcome == AskOutcome.ANSWERED -> "（聞き取った文がありません）"
  ask.failure == "no_speech" -> "（聞き取れませんでした）"
  ask.failure == "timeout" -> "（時間内に答えが届きませんでした）"
  else -> "（うまく答えられませんでした）"
}

sealed interface TimelineRow {
  data class Day(val label: String) : TimelineRow
  /** memo: the ask has a memo (US-D-04.005). unreached: it never reached the Backend (slice 4 spec §4.3, Android #65). */
  data class Ask(
    val askId: String,
    val number: Int,
    val time: String,
    val place: String,
    val text: String,
    val failed: Boolean,
    val memo: Boolean = false,
    val unreached: Boolean = false,
  ) : TimelineRow
  /** A point memo: its green number, when, where and the start of its text (the mock). */
  data class Note(val noteId: String, val number: Int, val time: String, val place: String, val text: String) : TimelineRow
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

/** order puts events of the same moment in a fixed order: a segment's start, then an ask, a point memo, a pause. */
private sealed interface Event {
  val t: Long
  val order: Int
  /** Shows nothing of itself, but a day the ride started or resumed on gets its heading (Android #51). */
  data class StartAt(override val t: Long, override val order: Int = 0) : Event
  data class AskAt(override val t: Long, val ask: AskRecord, override val order: Int = 1) : Event
  data class NoteAt(override val t: Long, val note: NoteRecord, override val order: Int = 2) : Event
  data class PauseAt(override val t: Long, val until: Long?, override val order: Int = 3) : Event
}

/**
 * SC-06's timeline: the ride's asks, its point memos, its pauses and the breaks between conversations, in time order.
 * A ride over more than one day gets a heading for each day, including a day it only resumed on (slice 2 spec §7.5,
 * Android #51). A paused ride ends with its pause so far.
 */
fun rideTimeline(ride: Ride, asks: List<AskRecord>, zone: ZoneId, notes: List<NoteRecord> = emptyList()): List<TimelineRow> {
  val numbers = askNumbers(asks)
  val noteNumbers = noteNumbers(notes)
  val starts = ride.segments.map { Event.StartAt(it.startedAt) }
  val pauses = ride.segments.zipWithNext { a, b -> Event.PauseAt(a.endedAt ?: b.startedAt, b.startedAt) }
  val open = if (ride.status == RideStatus.PAUSED) listOfNotNull(ride.segments.last().endedAt?.let { Event.PauseAt(it, null) }) else emptyList()
  val events = (starts + asks.map { Event.AskAt(it.askedAt, it) } + notes.map { Event.NoteAt(it.at, it) } + pauses + open)
    .sortedWith(compareBy<Event> { it.t }.thenBy { it.order })
  // Every day the ride recorded on counts, not only the days with an ask or a pause (Android #41).
  val recorded = ride.segments.flatMap { listOfNotNull(it.startedAt, it.endedAt) }
  val days = (events.map { it.t } + recorded).map { at(it, zone).toLocalDate() }.toSet()
  val rows = mutableListOf<TimelineRow>()
  var day: LocalDate? = null
  var asked = false
  events.forEach { event ->
    val date = at(event.t, zone).toLocalDate()
    if (days.size > 1 && date != day) rows += TimelineRow.Day(dayLabel(date))
    day = date
    when (event) {
      is Event.StartAt -> Unit
      is Event.NoteAt -> {
        val note = event.note
        rows += TimelineRow.Note(note.id, noteNumbers.getValue(note.id), TIME.format(at(note.at, zone)), notePlace(note), note.text)
      }
      is Event.AskAt -> {
        val ask = event.ask
        if (ask.newConversation && asked) rows += TimelineRow.NewConversation
        asked = true
        rows += TimelineRow.Ask(
          ask.askId, numbers.getValue(ask.askId), TIME.format(at(ask.askedAt, zone)), askPlace(ask), askText(ask),
          ask.outcome != AskOutcome.ANSWERED, memo = ask.memo != null, unreached = ask.outcome == AskOutcome.OFFLINE,
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

/**
 * SC-07. question is null when nothing was heard; failure is set for an ask that got no answer. An unreached ask has
 * neither: unreached says to show that it never reached the Backend instead (slice 4 spec §4.3).
 */
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
  /** The conversation's memo (SC-07); null when it has none. */
  val memo: String? = null,
  val unreached: Boolean = false,
)

/** SC-07's place of an unreached ask, in full: worded as SC-19 words a point memo's (slice 4 spec §4.3). */
private fun unreachedPlace(ask: AskRecord): String = when {
  ask.latitude == null -> "位置を記録できませんでした"
  ask.address != null -> ask.address
  ask.sent -> "住所は分かりませんでした"
  else -> "住所は送ったあとに付きます"
}

/** askId's place among the asks it was opened from (the ride's): 会話 3 / 5, and the asks before and after it. */
fun askDetail(asks: List<AskRecord>, askId: String, zone: ZoneId): AskDetailUi? {
  val ordered = asks.sortedBy { it.askedAt }
  val i = ordered.indexOfFirst { it.askId == askId }
  if (i < 0) return null
  val ask = ordered[i]
  val time = at(ask.askedAt, zone)
  val unreached = ask.outcome == AskOutcome.OFFLINE
  return AskDetailUi(
    title = "会話 ${i + 1} / ${ordered.size}",
    time = "${dayLabel(time.toLocalDate())} ${TIME.format(time)}",
    place = if (unreached) unreachedPlace(ask) else ask.address?.ifBlank { null } ?: "場所不明",
    direction = directionLabel(ask.headingDegrees),
    input = if (ask.input == "text") "⌨ 文字で質問" else "🎤 声で質問",
    question = ask.transcript?.ifBlank { null },
    answer = ask.answer?.ifBlank { null },
    failure = if (ask.outcome == AskOutcome.FAILED) failureLabel(ask.failure) else null,
    previous = ordered.getOrNull(i - 1)?.askId,
    next = ordered.getOrNull(i + 1)?.askId,
    memo = ask.memo,
    unreached = unreached,
  )
}

/**
 * The card of a tapped pin: 3 · 09:41 · 仙石原, then the question and the start of the answer; for a point memo, its
 * text. id is the askId or noteId the card opens.
 */
data class PinCardUi(val id: String, val heading: String, val question: String, val answer: String?, val kind: PinKind = PinKind.ASK)

fun pinCard(asks: List<AskRecord>, askId: String, zone: ZoneId): PinCardUi? {
  val ask = asks.firstOrNull { it.askId == askId } ?: return null
  val number = askNumbers(asks).getValue(askId)
  val answer = if (ask.outcome == AskOutcome.OFFLINE) UNREACHED_ANSWER else ask.answer?.ifBlank { null }
  return PinCardUi(askId, "$number · ${TIME.format(at(ask.askedAt, zone))} · ${askPlace(ask)}", askText(ask), answer)
}

/** A point memo's card: 2 · 10:24 · 新橋 · 📝 地点メモ, then its text (the mock). */
fun noteCard(notes: List<NoteRecord>, noteId: String, zone: ZoneId): PinCardUi? {
  val note = notes.firstOrNull { it.id == noteId } ?: return null
  val number = noteNumbers(notes).getValue(noteId)
  return PinCardUi(noteId, "$number · ${TIME.format(at(note.at, zone))} · ${notePlace(note)} · 📝 地点メモ", note.text, null, PinKind.NOTE)
}

/** SC-19's top: when, where and which ride the memo is in (slice 3 spec §7.3). */
data class NoteHeaderUi(val time: String, val where: String, val ride: String)

private const val NO_PLACE = "📍 位置を記録できませんでした"
private const val ADDRESS_TO_COME = "📍 住所は送ったあとに付きます"

/** A memo opened from Home: its address comes once it has been sent, as the Backend settles it (slice 3 spec §6.2). */
fun newNoteHeader(draft: NoteDraft, rideName: String, zone: ZoneId): NoteHeaderUi {
  val time = at(draft.at, zone)
  return NoteHeaderUi(
    "${dayLabel(time.toLocalDate())} ${TIME.format(time)} · 今いる地点",
    if (draft.latitude == null) NO_PLACE else ADDRESS_TO_COME,
    "走行「$rideName」に入ります",
  )
}

/** A kept memo opened from SC-06. One that has gone without an address is one the Backend could not tell. */
fun noteHeader(note: NoteRecord, rideName: String, zone: ZoneId): NoteHeaderUi {
  val time = at(note.at, zone)
  val where = when {
    note.latitude == null -> NO_PLACE
    note.address != null -> "📍 ${note.address}"
    note.sent -> "📍 住所は分かりませんでした"
    else -> ADDRESS_TO_COME
  }
  return NoteHeaderUi("${dayLabel(time.toLocalDate())} ${TIME.format(time)}", where, "走行「$rideName」で書いたメモ")
}

/** What SC-08 says it is writing for an ask: 会話 3 · 09:41 箱根町仙石原 (the mock). null when the ask is not among them. */
fun askMemoTarget(asks: List<AskRecord>, askId: String, zone: ZoneId): String? {
  val ask = asks.firstOrNull { it.askId == askId } ?: return null
  return "会話 ${askNumbers(asks).getValue(askId)} · ${TIME.format(at(ask.askedAt, zone))} ${askPlace(ask)}"
}
