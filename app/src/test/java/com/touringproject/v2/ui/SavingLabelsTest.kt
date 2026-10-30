package com.touringproject.v2.ui

import com.touringproject.v2.records.SaveSummary
import com.touringproject.v2.sync.BlockReason
import com.touringproject.v2.sync.OwnerState
import com.touringproject.v2.sync.TakeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val TOKYO = ZoneId.of("Asia/Tokyo")

private fun ms(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, TOKYO).toInstant().toEpochMilli()

class SavingLabelsTest {
  @Test
  fun a_mismatch_and_an_unconfirmed_name_are_told_and_the_rest_are_not() {
    assertEquals(
      "ユーザー名と端末の記録が合っていません（端末の記録の名前: taro01）。記録は送られていません",
      ownerNotice(OwnerState.MISMATCH, "taro01"),
    )
    assertEquals("ユーザー名と端末の記録が合っていません。記録は送られていません", ownerNotice(OwnerState.MISMATCH, null))
    assertEquals("ユーザー名を確かめられていません。記録は送られていません", ownerNotice(OwnerState.UNCONFIRMED, null))
    assertNull(ownerNotice(OwnerState.MATCH, "rider01"))
    assertNull(ownerNotice(OwnerState.UNKNOWN, null))
  }

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
    // Android #75: nothing before the first count.
    assertEquals("", saveRowValue(null))
  }

  @Test
  fun what_a_read_came_to_is_told_on_its_screen() {
    assertEquals("12件の記録を読み込みました", rereadMessage(TakeResult.Done(12)))
    assertEquals("新しい記録はありませんでした", rereadMessage(TakeResult.Done(0)))
    assertEquals("走行を終えてから読み直せます", rereadMessage(TakeResult.Blocked(BlockReason.RIDE)))
    assertEquals("読み込めませんでした", rereadMessage(TakeResult.Failed))
    assertEquals("ユーザー名を変えました", switchMessage(TakeResult.Done(0)))
    // Android #73: a ride that began after DL-03 is told as the ride, not as something left to send.
    assertEquals("走行を終えてから変えられます", switchMessage(TakeResult.Blocked(BlockReason.RIDE)))
    assertEquals("未送信の記録があるため、変えられませんでした", switchMessage(TakeResult.Blocked(BlockReason.UNSENT)))
    assertEquals("読み込めませんでした。ユーザー名は変えていません", switchMessage(TakeResult.Failed))
    // Android #88: the name could not be put back, so SC-18 tells what is so.
    assertEquals("ユーザー名は変わりましたが、記録を入れられませんでした。端末の記録は前の名前のままです", switchMessage(TakeResult.Mismatched))
  }
}
