package com.touringproject.v2.ride

// What a memo may hold, for the ride's own memo and for the memos of asks and point memos (US-D-04.003). Characters are
// counted as code points, as the Backend counts them (Backend #16).

/** US-D-04.003. */
const val MEMO_MAX = 2000

/** True when memo can be kept: none, or up to MEMO_MAX characters. */
fun memoFits(memo: String?): Boolean = memo == null || memo.codePointCount(0, memo.length) <= MEMO_MAX
