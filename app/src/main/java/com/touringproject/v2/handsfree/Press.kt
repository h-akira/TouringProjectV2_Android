package com.touringproject.v2.handsfree

// The same press can arrive twice (a recreated activity, a repeated intent); a press older than a minute is stale.
const val STALE_PRESS_MS = 60_000L

enum class Claim { NEW, DUPLICATE, STALE }

class PressTracker {
  private var lastHandled = 0L

  fun claim(pressedAt: Long, now: Long): Claim {
    if (pressedAt <= lastHandled) return Claim.DUPLICATE
    if (now - pressedAt > STALE_PRESS_MS) return Claim.STALE
    lastHandled = pressedAt
    return Claim.NEW
  }
}

/**
 * Holds presses that arrive before the app is ready (settings still loading, permission prompts at first launch)
 * and hands the newest one over once it is; claiming them earlier would mark them handled with nobody to act on them.
 */
class PressGate(private val handle: () -> Unit) {
  private var held: Long? = null
  private var open = false
  private val tracker = PressTracker()

  @Synchronized
  fun offer(pressedAt: Long, now: Long) {
    if (!open) {
      held = maxOf(held ?: 0L, pressedAt)
      return
    }
    if (tracker.claim(pressedAt, now) == Claim.NEW) handle()
  }

  @Synchronized
  fun openGate(now: Long) {
    open = true
    val waiting = held
    held = null
    if (waiting != null) offer(waiting, now)
  }
}
