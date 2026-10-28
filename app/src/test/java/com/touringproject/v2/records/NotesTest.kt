package com.touringproject.v2.records

import com.touringproject.v2.ride.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotesTest {
  // Slice 3 spec §4.3: the newest point is the place; slice 5 spec §4: the heading is the device's.
  @Test
  fun a_memo_is_at_the_newest_point_with_the_devices_heading() {
    val draft = noteDraft("n1", "r1", 70_000, RoutePoint(0, 60_000, 35.0201, 139.0, 5.0), 271.5)
    assertEquals(NoteDraft("n1", "r1", 70_000, 35.0201, 139.0, 271.5), draft)
  }

  @Test
  fun without_a_point_the_memo_has_no_place() {
    assertEquals(NoteDraft("n1", "r1", 70_000, null, null, null), noteDraft("n1", "r1", 70_000, null, null))
  }

  @Test
  fun a_memo_keeps_its_text_trimmed_and_a_blank_one_is_none() {
    assertEquals("展望スペース", noteText("  展望スペース \n"))
    assertNull(noteText(" \n "))
  }

  // Slice 3 spec §7.3: what was heard goes at the end of what is written, on a line of its own, within 2000 characters.
  @Test
  fun what_was_heard_goes_at_the_end_on_a_line_of_its_own() {
    assertEquals("展望スペース", appendHeard("", "展望スペース"))
    assertEquals("料金所の手前\n展望スペース", appendHeard("料金所の手前", "展望スペース"))
    assertEquals("料金所の手前\n展望スペース", appendHeard("料金所の手前\n", "展望スペース"))
    val full = appendHeard("あ".repeat(1998), "いうえ")
    assertEquals(2000, full.codePointCount(0, full.length))
  }
}
