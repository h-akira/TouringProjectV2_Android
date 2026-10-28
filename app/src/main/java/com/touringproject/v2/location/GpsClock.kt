package com.touringproject.v2.location

/**
 * The present on the fixes' own (often GNSS) time, which may be off the device's clock. Route points read back from the
 * database keep only that time, so how old one is (the heading's hold, slice 5 spec §4.2) is told against this clock,
 * not the device's (Android #17). Each fix seen tells how its time stands to the time since boot; until one has been
 * seen in this process, the device's clock stands in.
 */
class GpsClock(private val elapsed: () -> Long, private val clock: () -> Long) {
  @Volatile private var offset: Long? = null

  fun seen(fix: Fix) {
    fix.elapsedMs?.let { offset = fix.timestamp - it }
  }

  fun now(): Long = offset?.let { elapsed() + it } ?: clock()
}

/** The same route, each fix shown to clock on the way. */
fun RouteSource.seenBy(clock: GpsClock): RouteSource {
  val source = this
  return RouteSource { onFix -> source.watch { clock.seen(it); onFix(it) } }
}

/** The same positions, each fix shown to clock on the way. */
fun PositionSource.seenBy(clock: GpsClock): PositionSource {
  val source = this
  return object : PositionSource {
    override fun now() = source.now()
    override fun elapsed() = source.elapsed()
    override fun lastKnown(onFix: (Fix) -> Unit) = source.lastKnown { clock.seen(it); onFix(it) }
    override fun watch(onFix: (Fix) -> Unit) = source.watch { clock.seen(it); onFix(it) }
  }
}
