package com.touringproject.v2.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemosTest {
  @Test
  fun a_memo_is_trimmed_and_a_blank_one_is_none() {
    assertEquals("また来たい", memoText("  また来たい\n"))
    assertNull(memoText(" \n "))
  }

  @Test
  fun the_field_keeps_2000_characters_counting_an_emoji_as_one() {
    assertEquals(2000, cutMemo("😀".repeat(2001)).codePointCount(0, cutMemo("😀".repeat(2001)).length))
    assertEquals("😀".repeat(2000), cutMemo("😀".repeat(2000)))
    assertEquals("あ".repeat(2000), cutMemo("あ".repeat(2500)))
  }

  // Android #61: an emoji made of several code points is kept whole or left out whole at the 2000th character.
  @Test
  fun the_cut_splits_no_emoji_made_of_several_code_points() {
    val family = "👨‍👩‍👧"
    assertEquals("あ".repeat(1997), cutMemo("あ".repeat(1997) + family))
    assertEquals("あ".repeat(1995) + family, cutMemo("あ".repeat(1995) + family + "い"))
    assertEquals("あ".repeat(1999), cutMemo("あ".repeat(1999) + "🇯🇵"))
    assertEquals("あ".repeat(1997) + "🇯🇵", cutMemo("あ".repeat(1997) + "🇯🇵🇯🇵"))
    assertEquals("あ".repeat(1999), cutMemo("あ".repeat(1999) + "👍🏽"))
    assertEquals("あ".repeat(1999), cutMemo("あ".repeat(1999) + "❤️"))
  }

  @Test
  fun a_memo_fits_up_to_2000_characters() {
    assertTrue(memoFits(null))
    assertTrue(memoFits("😀".repeat(2000)))
    assertFalse(memoFits("あ".repeat(2001)))
  }

  @Test
  fun a_memo_change_is_always_after_the_one_before() {
    assertEquals(5_000L, memoTime(5_000, null))
    assertEquals(5_000L, memoTime(5_000, 4_000))
    assertEquals(9_001L, memoTime(5_000, 9_000))
  }
}
