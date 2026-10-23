package com.touringproject.v2.ui

import com.touringproject.v2.records.SaveSummary
import com.touringproject.v2.sync.TakeResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val TOKYO = ZoneId.of("Asia/Tokyo")

private fun ms(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, TOKYO).toInstant().toEpochMilli()

class SavingLabelsTest {
  // Slice 4 spec §7.1 (the breakdown settled in plan 3): only the kinds with any, in one line.
  @Test
  fun the_breakdown_names_the_kinds_left_to_send() {
    assertEquals(
      "質問できなかった地点 1件・走行 2本・地点メモ 3件・メモ 1件・削除 1件",
      unsentBreakdown(SaveSummary(unreached = 1, unsentRides = 2, notes = 3, memos = 1, deletions = 1)),
    )
    assertEquals("走行 1本・削除 2件", unsentBreakdown(SaveSummary(unsentRides = 1, deletions = 2, rides = 5)))
  }

  @Test
  fun the_last_save_shows_its_day_unless_it_was_today() {
    assertEquals("最終保存 10:22", lastSavedLabel(ms(22, 10, 22), ms(22, 18, 0), TOKYO))
    assertEquals("最終保存 10月21日 23:05", lastSavedLabel(ms(21, 23, 5), ms(22, 8, 0), TOKYO))
    assertEquals("まだ保存していません", lastSavedLabel(null, ms(22, 8, 0), TOKYO))
  }

  @Test
  fun the_settings_row_tells_whether_anything_is_left() {
    assertEquals("保存済み", saveRowValue(SaveSummary(rides = 3, asks = 9)))
    assertEquals("未送信あり", saveRowValue(SaveSummary(memos = 1)))
  }

  @Test
  fun what_a_read_came_to_is_told_on_its_screen() {
    assertEquals("12件の記録を読み込みました", rereadMessage(TakeResult.Done(12)))
    assertEquals("新しい記録はありませんでした", rereadMessage(TakeResult.Done(0)))
    assertEquals("走行を終えてから読み直せます", rereadMessage(TakeResult.Blocked))
    assertEquals("読み込めませんでした", rereadMessage(TakeResult.Failed))
    assertEquals("ユーザー名を変えました", switchMessage(TakeResult.Done(0)))
    assertEquals("未送信の記録があるため、変えられませんでした", switchMessage(TakeResult.Blocked))
    assertEquals("読み込めませんでした。ユーザー名は変えていません", switchMessage(TakeResult.Failed))
  }
}
