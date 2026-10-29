package com.touringproject.v2.location

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// Collects position fixes while a question is recorded, only to tell where it was asked: the heading comes from the
// ride's route (slice 5 spec §4.1). A last-known point older than MAX_CURRENT_AGE_MS is not sent as the current
// position, and callbacks that belong to an earlier begin() are ignored.
const val MAX_CURRENT_AGE_MS = 30_000L
const val LATEST_WAIT_MS = 2_000L

fun interface Subscription {
  fun remove()
}

interface PositionSource {
  /** The device's clock. */
  fun now(): Long
  /** The time since boot (SystemClock.elapsedRealtime). */
  fun elapsed(): Long
  fun lastKnown(onFix: (Fix) -> Unit)
  fun watch(onFix: (Fix) -> Unit): Subscription
}

/**
 * Whether the fix is at most MAX_CURRENT_AGE_MS old: by the time since boot when the fix tells it, since the fix's own
 * time may be off the device's clock (Android #17); otherwise by the clock.
 */
fun Fix.isFresh(now: Long, elapsed: Long): Boolean = (elapsedMs?.let { elapsed - it } ?: (now - timestamp)) <= MAX_CURRENT_AGE_MS

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
  override fun end(): Fix? {
    generation += 1
    subscription?.remove()
    subscription = null
    val latest = fixes.maxByOrNull { it.timestamp }
    fixes.clear()
    return latest?.takeIf { it.isFresh(source.now(), source.elapsed()) }
  }

  /**
   * The ride's route keeps Fused's last fix fresh while a ride records (every 5 s), which is when text asks are taken.
   * None comes when there is no fix, or no permission; the wait is cut off at LATEST_WAIT_MS.
   */
  override suspend fun latest(): Fix? {
    val fix = withTimeoutOrNull(LATEST_WAIT_MS) {
      suspendCancellableCoroutine<Fix?> { cont ->
        runCatching { source.lastKnown { if (cont.isActive) cont.resume(it) } }.onFailure { if (cont.isActive) cont.resume(null) }
      }
    }
    return fix?.takeIf { it.isFresh(source.now(), source.elapsed()) }
  }
}
