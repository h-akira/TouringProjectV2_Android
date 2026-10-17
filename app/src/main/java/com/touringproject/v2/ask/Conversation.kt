package com.touringproject.v2.ask

import java.time.Instant

// The conversation id the Backend issued and when it lapses (adr/0003). Kept in memory only.
class ConversationState {
  private var id: String? = null
  private var expiry = 0L

  fun current(now: Long): String? {
    if (id != null && now < expiry) return id
    reset()
    return null
  }

  fun update(id: String, expiresAtIso: String) {
    val parsed = runCatching { Instant.parse(expiresAtIso).toEpochMilli() }.getOrNull()
    if (parsed == null) {
      reset()
      return
    }
    this.id = id
    expiry = parsed
  }

  fun reset() {
    id = null
    expiry = 0
  }

  fun expiresAt(now: Long): Long? = if (current(now) == null) null else expiry
}
