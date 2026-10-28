package com.touringproject.v2.records

// Memos on rides and asks (US-D-04, SC-08). Characters are counted as code points, as the Backend counts them, so an
// emoji is never cut in half (Backend #16).

/** US-D-04.003. */
const val MEMO_MAX = 2000

private fun String.characters() = codePointCount(0, length)

/** The memo a typed text keeps: trimmed, and none when it is blank (SC-08: saving an empty memo removes it). */
fun memoText(input: String): String? = input.trim().ifEmpty { null }

/**
 * What the memo field keeps of a typed or pasted text: its first 2000 characters, cut where no character on screen is
 * split (Android #61).
 */
fun cutMemo(input: String): String =
  if (input.characters() <= MEMO_MAX) input else input.substring(0, characterEnd(input, input.offsetByCodePoints(0, MEMO_MAX)))

// A code point that belongs with the one before it on screen: a joiner, a variation selector, a skin tone, a keycap, a
// tag, a combining mark. Written out because the JDK's BreakIterator joins emoji only from Java 20 on.
private fun joinsBefore(cp: Int): Boolean =
  cp == ZWJ || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || cp in 0xE0020..0xE007F ||
    Character.getType(cp).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }

private const val ZWJ = 0x200D

private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

private fun regionalsBefore(s: String, end: Int): Int {
  var count = 0
  var i = end
  while (i > 0 && isRegional(s.codePointBefore(i))) {
    count += 1
    i = s.offsetByCodePoints(i, -1)
  }
  return count
}

/** The end at or before end that splits no character on screen: an emoji joined with ZWJ, a flag, a skin tone. */
private fun characterEnd(s: String, end: Int): Int {
  var e = end
  while (e in 1 until s.length) {
    val next = s.codePointAt(e)
    val before = s.codePointBefore(e)
    val splits = joinsBefore(next) || before == ZWJ || (isRegional(next) && regionalsBefore(s, e) % 2 == 1)
    if (!splits) break
    e = s.offsetByCodePoints(e, -1)
  }
  return e
}

/** True when memo can be kept: none, or up to MEMO_MAX characters. */
fun memoFits(memo: String?): Boolean = memo == null || memo.characters() <= MEMO_MAX

/**
 * When a memo changes: now, but always after the change before it, so the Backend, which keeps only a newer version,
 * takes it even when the device's clock has been set back.
 */
fun memoTime(now: Long, previous: Long?): Long = if (previous == null) now else maxOf(now, previous + 1)
