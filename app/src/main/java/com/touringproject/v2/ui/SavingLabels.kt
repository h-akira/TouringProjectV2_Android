package com.touringproject.v2.ui

import com.touringproject.v2.records.SaveSummary
import com.touringproject.v2.sync.TakeResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// How SC-14, SC-09's データの保存 and SC-18 tell what is saved and what a read of the cloud came to (slice 4 spec §7).

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** SC-14's 内訳, the kinds with any in one line: 質問できなかった地点 1件・走行 2本・地点メモ 3件・メモ 1件・削除 1件. */
fun unsentBreakdown(summary: SaveSummary): String = listOf(
  summary.unreached to { n: Int -> "質問できなかった地点 ${n}件" },
  summary.unsentRides to { n: Int -> "走行 ${n}本" },
  summary.notes to { n: Int -> "地点メモ ${n}件" },
  summary.memos to { n: Int -> "メモ ${n}件" },
  summary.deletions to { n: Int -> "削除 ${n}件" },
).filter { it.first > 0 }.joinToString("・") { (n, label) -> label(n) }

/** 最終保存 10:22, with the day when it was not today; まだ保存していません before the first save. */
fun lastSavedLabel(at: Long?, now: Long, zone: ZoneId): String {
  if (at == null) return "まだ保存していません"
  val saved = Instant.ofEpochMilli(at).atZone(zone)
  val time = CLOCK.format(saved)
  return if (saved.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) "最終保存 $time" else "最終保存 ${dayLabel(saved.toLocalDate(), weekday = false)} $time"
}

/** SC-09's データの保存 row. */
fun saveRowValue(summary: SaveSummary): String = if (summary.unsent > 0) "未送信あり" else "保存済み"

/** SC-14 after クラウドから読み直す. */
fun rereadMessage(result: TakeResult): String = when (result) {
  is TakeResult.Done -> if (result.count == 0) "新しい記録はありませんでした" else "${result.count}件の記録を読み込みました"
  TakeResult.Blocked -> "走行を終えてから読み直せます"
  TakeResult.Failed -> "読み込めませんでした"
}

/** SC-18 after DL-03. */
fun switchMessage(result: TakeResult): String = when (result) {
  is TakeResult.Done -> "ユーザー名を変えました"
  TakeResult.Blocked -> "未送信の記録があるため、変えられませんでした"
  TakeResult.Failed -> "読み込めませんでした。ユーザー名は変えていません"
}
