package com.touringproject.v2.sync

import com.touringproject.v2.api.CloudRecord

// Reading the user's records back from the cloud (slice 4 spec §6, adr/0010): when the user name changes, and when SC-14
// reads them again.

interface TakeoverStore {
  /**
   * Merges the cloud's records into the device's (slice 4 spec §6.1) in one transaction; with replace, all the device
   * keeps goes first (§6.4: another user name). Returns how many records came in or went, or null when nothing was done
   * because a ride is recording or paused or, with replace, something is left to send (US-E-06.008). A failure throws
   * and changes nothing.
   */
  suspend fun take(cloud: List<CloudRecord>, replace: Boolean, now: Long): Int?
}
