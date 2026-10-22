package com.touringproject.v2.records

import com.touringproject.v2.ride.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotesTest {
  private fun p(t: Long, lat: Double, lon: Double, accuracy: Double? = 5.0) = RoutePoint(0, t, lat, lon, accuracy)

  // Slice 3 spec §4.3: the newest point is the place, and an older one far enough back is the heading's sample.
  @Test
  fun a_memo_is_at_the_newest_point_with_an_older_one_for_the_heading() {
    val recent = listOf(p(1_000, 35.000, 139.000), p(30_000, 35.001, 139.000), p(55_000, 35.002, 139.000), p(60_000, 35.0201, 139.000))
    val draft = noteDraft("n1", "r1", 70_000, recent)
    assertEquals(NoteDraft("n1", "r1", 70_000, 35.0201, 139.000, 35.002, 139.000, 55_000), draft)
  }

  @Test
  fun without_a_point_the_memo_has_no_place() {
    assertEquals(NoteDraft("n1", "r1", 70_000, null, null, null, null, null), noteDraft("n1", "r1", 70_000, emptyList()))
  }

  // The same rules as an ask's sample: none closer than 15 m, older than a minute, or from a poor fix.
  @Test
  fun the_heading_sample_is_left_out_when_no_older_point_will_do() {
    val near = listOf(p(50_000, 35.0000, 139.0), p(60_000, 35.0001, 139.0))
    assertNull(noteDraft("n1", "r1", 70_000, near).sampleAt)
    val old = listOf(p(1_000, 35.0, 139.0), p(90_000, 35.01, 139.0))
    assertNull(noteDraft("n1", "r1", 95_000, old).sampleAt)
    val poor = listOf(p(50_000, 35.0, 139.0, accuracy = 80.0), p(60_000, 35.01, 139.0))
    assertNull(noteDraft("n1", "r1", 70_000, poor).sampleAt)
  }

  @Test
  fun a_memo_keeps_its_text_trimmed_and_a_blank_one_is_none() {
    assertEquals("展望スペース", noteText("  展望スペース \n"))
    assertNull(noteText(" \n "))
  }
}
