package com.touringproject.v2.records

// Memos on rides and asks (US-D-04, SC-08). Characters are counted as code points, as the Backend counts them, so an
// emoji is never cut in half (Backend #16).

/** US-D-04.003. */
const val MEMO_MAX = 2000

private fun String.characters() = codePointCount(0, length)

/** The memo a typed text keeps: trimmed, and none when it is blank (SC-08: saving an empty memo removes it). */
fun memoText(input: String): String? = input.trim().ifEmpty { null }

/** What the memo field keeps of a typed or pasted text: its first 2000 characters. */
fun cutMemo(input: String): String =
  if (input.characters() <= MEMO_MAX) input else input.substring(0, input.offsetByCodePoints(0, MEMO_MAX))

/** True when memo can be kept: none, or up to MEMO_MAX characters. */
fun memoFits(memo: String?): Boolean = memo == null || memo.characters() <= MEMO_MAX

/**
 * When a memo changes: now, but always after the change before it, so the Backend, which keeps only a newer version,
 * takes it even when the device's clock has been set back.
 */
fun memoTime(now: Long, previous: Long?): Long = if (previous == null) now else maxOf(now, previous + 1)
