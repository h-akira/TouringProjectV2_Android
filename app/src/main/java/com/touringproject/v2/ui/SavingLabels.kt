package com.touringproject.v2.ui

import com.touringproject.v2.records.SaveSummary
import com.touringproject.v2.sync.BlockReason
import com.touringproject.v2.sync.OwnerState
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

/** SC-09's データの保存 row; empty until what is left has been counted (Android #75). */
fun saveRowValue(summary: SaveSummary?): String = when {
  summary == null -> ""
  summary.unsent > 0 -> "未送信あり"
  else -> "保存済み"
}

/** SC-14 after クラウドから読み直す. */
fun rereadMessage(result: TakeResult): String = when (result) {
  is TakeResult.Done -> if (result.count == 0) "新しい記録はありませんでした" else "${result.count}件の記録を読み込みました"
  is TakeResult.Blocked -> when (result.reason) {
    BlockReason.OWNER -> "ユーザー名と端末の記録が合っていません。ユーザー名の変更から直してください"
    else -> "走行を終えてから読み直せます"
  }
  // A read again changes no name, so it never comes to Mismatched.
  TakeResult.Failed, TakeResult.Mismatched -> "読み込めませんでした"
}

/** SC-18 after DL-03. */
fun switchMessage(result: TakeResult): String = when (result) {
  is TakeResult.Done -> "ユーザー名を変えました"
  is TakeResult.Blocked -> when (result.reason) {
    BlockReason.RIDE -> "走行を終えてから変えられます"
    BlockReason.UNSENT -> "未送信の記録があるため、変えられませんでした"
    BlockReason.WAITING -> "走行の変更を端末に書き込めていないため、変えられませんでした"
    BlockReason.OWNER -> "変えられませんでした"
  }
  TakeResult.Failed -> "読み込めませんでした。ユーザー名は変えていません"
  TakeResult.Mismatched -> "ユーザー名は変わりましたが、記録を入れられませんでした。端末の記録は前の名前のままです"
}

/**
 * What SC-14 and SC-18 tell when the records are not sent for the owner's sake (adr/0015); null when nothing is wrong. A
 * mismatch names the owner (ownerName, when known), so the rider can choose that name again in SC-18.
 */
fun ownerNotice(state: OwnerState, ownerName: String?): String? = when (state) {
  OwnerState.MISMATCH ->
    if (ownerName == null) "ユーザー名と端末の記録が合っていません。記録は送られていません"
    else "ユーザー名と端末の記録が合っていません（端末の記録の名前: $ownerName）。記録は送られていません"
  OwnerState.UNCONFIRMED -> "ユーザー名を確かめられていません。記録は送られていません"
  OwnerState.MATCH, OwnerState.UNKNOWN -> null
}

/** Why the user name cannot change now (SC-18, US-E-06.008). */
fun blockMessage(reason: BlockReason): String = when (reason) {
  BlockReason.RIDE -> "走行を終えてから変えられます"
  BlockReason.UNSENT -> "未送信の記録があります。先に「データの保存」で送ってください"
  BlockReason.WAITING -> "走行の変更を端末に書き込めていません。端末の空き容量を確かめてください"
  BlockReason.OWNER -> "ユーザー名と端末の記録が合っていません。ユーザー名の変更から直してください"
}
