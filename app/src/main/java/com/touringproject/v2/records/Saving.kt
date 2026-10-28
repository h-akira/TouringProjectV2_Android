package com.touringproject.v2.records

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * What SC-14 tells of the device's records (slice 4 spec §7.1). The first five count what the next pass would send:
 * unreached asks, rides (their meta or route), point memos, conversation memos and deletions; a version the Backend
 * refused is not counted, since it is never sent again. The ride recording now is left out: its last, partial chunk
 * goes only at its pause or end (slice 2 spec §5.3). rides and asks count all the device keeps.
 */
data class SaveSummary(
  val unreached: Int = 0,
  val unsentRides: Int = 0,
  val notes: Int = 0,
  val memos: Int = 0,
  val deletions: Int = 0,
  val rides: Int = 0,
  val asks: Int = 0,
) {
  val unsent: Int get() = unreached + unsentRides + notes + memos + deletions
}

/**
 * SC-14's and SC-18's summary, counted while a screen shows it and for 5 s after; null until it is counted. Once it is
 * no longer counted it is null again, so a screen coming back never shows an old count (Android #90).
 */
fun Flow<SaveSummary>.whileShown(scope: CoroutineScope): StateFlow<SaveSummary?> =
  stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)
