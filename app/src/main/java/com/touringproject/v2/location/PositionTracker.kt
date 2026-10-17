package com.touringproject.v2.location

import com.touringproject.v2.ask.Positions

// Collects position fixes while a question is recorded. A last-known point older than MAX_CURRENT_AGE_MS is not
// sent as the current position, and callbacks that belong to an earlier begin() are ignored.
const val MAX_CURRENT_AGE_MS = 30_000L

fun interface Subscription {
  fun remove()
}

interface PositionSource {
  fun now(): Long
  fun lastKnown(onFix: (Fix) -> Unit)
  fun watch(onFix: (Fix) -> Unit): Subscription
}

class PositionTracker(private val source: PositionSource) : Positions {
  private val fixes = mutableListOf<Fix>()
  private var subscription: Subscription? = null
  // Bumped by begin() and end(); callbacks of an earlier generation are ignored.
  private var generation = 0

  @Synchronized
  override fun begin() {
    subscription?.remove()
    subscription = null
    fixes.clear()
    val gen = ++generation
    val add: (Fix) -> Unit = { fix -> synchronized(this) { if (gen == generation) fixes.add(fix) } }
    runCatching { source.lastKnown(add) }
    subscription = runCatching { source.watch(add) }.getOrNull()
  }

  @Synchronized
  override fun end(): Pair<Fix?, List<Fix>> {
    generation += 1
    subscription?.remove()
    subscription = null
    val sorted = fixes.sortedBy { it.timestamp }
    fixes.clear()
    val latest = sorted.lastOrNull()
    val current = latest?.takeIf { source.now() - it.timestamp <= MAX_CURRENT_AGE_MS }
    return current to sorted
  }
}
