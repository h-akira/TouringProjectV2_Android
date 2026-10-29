package com.touringproject.v2.location

interface Positions {
  fun begin()
  /** Where the ask is: the newest fix since begin(), unless it is older than 30 s. */
  fun end(): Fix?
  /** Where a text ask is (slice 5 spec §4.1): the device's last known fix, unless it is older than 30 s. */
  suspend fun latest(): Fix?
}
