package com.touringproject.v2.sync

/** Whose records the device keeps (adr/0015). */
interface OwnerStore {
  /** The user name the records are kept under; null before any was taken. */
  suspend fun owner(): String?

  /** Takes userName as the owner. */
  suspend fun adopt(userName: String)
}
