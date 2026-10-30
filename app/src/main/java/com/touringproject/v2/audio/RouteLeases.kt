package com.touringproject.v2.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/** The longest a user waits for the one before to let go of the route. */
const val LEASE_WAIT_MS = 8_000L

class RouteBusyException : IllegalStateException("the audio route is held by another user")

/**
 * Hands the one audio route to one user at a time (Android R10-01). A user that is cancelled while it waits or while the
 * route comes up leaves nothing held, and a lease let go of twice acts once, so the cleanup of an ask that was
 * cancelled can never let go of the route of the ask after it.
 */
class RouteLeases(private val driver: RouteDriver, private val waitMs: Long = LEASE_WAIT_MS) : RouteControl {
  private val gate = Mutex()

  override suspend fun lease(source: Source): RouteLease {
    var locked = false
    try {
      withTimeout(waitMs) {
        gate.lock()
        locked = true
      }
    } catch (e: CancellationException) {
      if (locked) gate.unlock()
      throw if (e is TimeoutCancellationException) RouteBusyException() else e
    }
    try {
      return Lease(driver.start(source))
    } catch (e: Throwable) {
      gate.unlock()
      throw e
    }
  }

  private inner class Lease(override val routed: Boolean) : RouteLease {
    private val released = AtomicBoolean(false)

    override fun inputDevice(): InputDevice? = driver.inputDevice()

    override suspend fun release() {
      if (!released.compareAndSet(false, true)) return
      try {
        withContext(NonCancellable) { driver.stop() }
      } finally {
        gate.unlock()
      }
    }
  }
}

/**
 * One try to take the route that its owner can call off from another coroutine, e.g. the big button while the route
 * is still coming up. [take] gives null then.
 */
class LeaseRequest(private val route: RouteControl, private val source: Source) {
  private var attempt: Deferred<RouteLease>? = null
  private var calledOff = false

  suspend fun take(): RouteLease? = coroutineScope {
    val job: Deferred<RouteLease> = async { route.lease(source) }
    attempt = job
    if (calledOff) job.cancel()
    try {
      job.await()
    } catch (e: CancellationException) {
      // The caller's own cancellation goes on; only a call-off ends here.
      currentCoroutineContext().ensureActive()
      null
    }
  }

  fun callOff() {
    calledOff = true
    attempt?.cancel()
  }
}
