package com.touringproject.v2.records

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
