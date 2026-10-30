package com.touringproject.v2.store

// The one place that says what is "not sent yet" in SQL (08a §6, slice 6 plan 5). The columns are the unprefixed ones of
// the table a query reads, so a correlated subquery finds its own table's first.

/** The version in the row has not been taken by the Backend. */
internal const val VERSION_NOT_TAKEN = "(sentUpdatedAt IS NULL OR sentUpdatedAt < updatedAt)"

/** The Backend did not refuse this very version (a refused one is never sent again). */
internal const val VERSION_NOT_REFUSED = "(rejectedUpdatedAt IS NULL OR rejectedUpdatedAt != updatedAt)"

/** What the next pass sends and what SC-14 counts: not taken, and not refused. */
internal const val UNSENT = "$VERSION_NOT_TAKEN AND $VERSION_NOT_REFUSED"

/** An ask that never reached the Backend and is not sent yet (slice 4 spec §5.1). */
internal const val UNREACHED_UNSENT = "status = 'offline' AND $UNSENT"

/** An ask's memo that is not sent yet. */
internal const val MEMO_UNSENT =
  "memoUpdatedAt IS NOT NULL AND (memoSentUpdatedAt IS NULL OR memoSentUpdatedAt < memoUpdatedAt) AND " +
    "(memoRejectedUpdatedAt IS NULL OR memoRejectedUpdatedAt != memoUpdatedAt)"

/** A ride's route points waiting for a chunk to go. */
internal const val POINTS_UNSENT = "EXISTS (SELECT 1 FROM route_points WHERE chunk IS NULL AND route_points.rideId = rides.id)"

// SC-05's list marks are a different question, "is something of it not taken": a refused version counts there, since the
// rider has not got it saved (slice 2 spec §5.4). They use VERSION_NOT_TAKEN alone, under names that say so.
internal const val NOTE_LEFT = VERSION_NOT_TAKEN
internal const val UNREACHED_LEFT = "status = 'offline' AND $VERSION_NOT_TAKEN"
internal const val MEMO_LEFT = "memoUpdatedAt IS NOT NULL AND (memoSentUpdatedAt IS NULL OR memoSentUpdatedAt < memoUpdatedAt)"
