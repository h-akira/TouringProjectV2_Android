package com.touringproject.v2.records

/** What SC-07 and SC-08 change of a kept ask (US-D-04.001, US-D-06.001). Each returns false when nothing was written. */
interface AskEdits {
  /** memo is the cleaned memo (memoText), or null to remove it. */
  suspend fun memo(askId: String, memo: String?): Boolean
  /** Removes the ask and marks it to be deleted on the Backend too (slice 3 spec §4.2). */
  suspend fun delete(askId: String): Boolean
}
