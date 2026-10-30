package com.touringproject.v2.location

interface Positions {
  /** Starts collecting fixes; the token tells this begin from a later one. */
  fun begin(): Int
  /**
   * Where the ask is: the newest fix since the begin() that gave the token, unless it is older than 30 s. A token that
   * a later begin() has replaced changes nothing and gives none: the cleanup of a cancelled ask must not end the next
   * ask's watch (Android R1-01).
   */
  fun end(token: Int): Fix?
  /** Where a text ask is (slice 5 spec §4.1): the device's last known fix, unless it is older than 30 s. */
  suspend fun latest(): Fix?
}
