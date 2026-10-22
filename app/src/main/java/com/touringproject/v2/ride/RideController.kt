package com.touringproject.v2.ride

import com.touringproject.v2.location.Fix
import com.touringproject.v2.location.acceptRoutePoint
import com.touringproject.v2.records.memoFits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Starts, pauses, resumes and ends the ride, and takes in its route points. The service, the deadline alarm, the
 * shutdown broadcast and the screens all call in here; one lock keeps their calls in order. Every call waits for
 * load(), which settles a ride left over from before the process started. A failed write is logged and shown
 * (storeFailed) but never stops the ride (slice 2 spec §8). A ride whose save failed is saved again before anything
 * else, so its points have a row to belong to and an end is not undone by the next launch. Once a call holds the lock
 * it runs to the end even if its caller is cancelled (a screen that goes away), so memory, the database, the alarm and
 * the service never part.
 */
class RideController(private val deps: RideDeps) {
  private val mutex = Mutex()
  private val loaded = CompletableDeferred<Unit>()
  private val activeRide = MutableStateFlow<Ride?>(null)
  /** The ride that is recording or paused; null when none is. */
  val active: StateFlow<Ride?> = activeRide.asStateFlow()
  private val storeFailing = MutableStateFlow(false)
  /** True while the last write to the device's database failed. */
  val storeFailed: StateFlow<Boolean> = storeFailing.asStateFlow()
  // The last stored point of the active ride, for the distance of the next one.
  private var lastPoint: RoutePoint? = null
  // A ride whose save failed; it is written again first by every later call.
  private var unsaved: Ride? = null
  // False until the ride left over from before this process has been read; until then no new ride may start.
  private var reconciled = false

  suspend fun load() {
    mutex.withLock {
      if (loaded.isCompleted) return
      withContext(NonCancellable) { reconcileNow() }
      loaded.complete(Unit)
    }
  }

  // A failed read leaves reconciled false, so the next call tries again instead of taking the database as empty.
  private suspend fun reconcileNow() {
    val d = deps
    val ride = try {
      d.store.active()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      failed("active", e)
      return
    }
    reconciled = true
    storeFailing.value = unsaved != null
    val now = d.now()
    val decision = reconcile(ride, now, d.bootCount())
    d.log("ride_load", mapOf("ride" to ride?.id, "decision" to decision.toString()))
    when (decision) {
      Reconcile.Nothing -> Unit
      is Reconcile.EndShutdown -> finish(ride!!.end(decision.at, EndReason.SHUTDOWN, now))
      is Reconcile.EndAuto -> finish(ride!!.end(decision.at, EndReason.AUTO, now))
      Reconcile.KeepPaused -> activeRide.value = ride
      Reconcile.Resume -> {
        activeRide.value = ride
        lastPoint = read("last_point") { d.store.lastPoint(ride!!.id) }
        d.alarm.set(ride!!.id, ride.deadline!!)
        d.recording(true)
      }
    }
  }

  private suspend fun <T> read(op: String, block: suspend () -> T?): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    failed(op, e)
    null
  }

  /** False when the write failed (logged and shown). */
  private suspend fun write(op: String, block: suspend () -> Unit): Boolean {
    try {
      block()
      storeFailing.value = unsaved != null
      return true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      failed(op, e)
      return false
    }
  }

  private fun failed(op: String, e: Exception) {
    deps.log("store_failed", mapOf("op" to op, "error" to e.toString()))
    storeFailing.value = true
  }

  /** False when the write failed: the ride stays unsaved and the next call writes it again first. */
  private suspend fun save(ride: Ride): Boolean {
    unsaved = ride
    val saved = write("save") {
      deps.store.save(ride)
      unsaved = null
    }
    if (saved) deps.changed()
    return saved
  }

  // The active ride's points and alive time have moved on since its save failed: save its current state, not the old one.
  private suspend fun flush() {
    val pending = unsaved ?: return
    save(activeRide.value?.takeIf { it.id == pending.id } ?: pending)
  }

  private suspend fun <T> locked(block: suspend () -> T): T {
    loaded.await()
    return mutex.withLock {
      withContext(NonCancellable) {
        flush()
        if (!reconciled) reconcileNow()
        block()
      }
    }
  }

  /**
   * Returns false when the name is not usable, another ride is already recording or paused, or the database has not
   * yet told which (a failed read, or an earlier ride's end still unsaved).
   */
  suspend fun start(name: String): Boolean = locked {
    val d = deps
    val clean = rideName(name)
    if (clean == null || !reconciled || unsaved != null || activeRide.value != null) return@locked false
    val ride = newRide(d.newId(), clean, d.now(), d.bootCount(), d.maxRecordingMs())
    save(ride)
    activeRide.value = ride
    lastPoint = null
    d.alarm.set(ride.id, ride.deadline!!)
    d.recording(true)
    d.log("ride_start", mapOf("ride" to ride.id))
    true
  }

  /**
   * Returns true when the ride is now paused. A ride already past its deadline ends automatically at the deadline
   * instead (Android #15), and false tells the screen that nothing was paused.
   */
  suspend fun pause(): Boolean = locked {
    val ride = activeRide.value?.takeIf { it.status == RideStatus.RECORDING } ?: return@locked false
    val d = deps
    val now = d.now()
    if (autoEndIfDue(ride, now)) return@locked false
    val paused = ride.pause(now)
    save(paused)
    activeRide.value = paused
    d.alarm.cancel()
    d.recording(false)
    true
  }

  /** Returns true when the ride is now recording again. */
  suspend fun resume(): Boolean = locked {
    val ride = activeRide.value?.takeIf { it.status == RideStatus.PAUSED } ?: return@locked false
    val d = deps
    val resumed = ride.resume(d.now(), d.bootCount(), d.maxRecordingMs())
    save(resumed)
    activeRide.value = resumed
    lastPoint = null
    d.alarm.set(resumed.id, resumed.deadline!!)
    d.recording(true)
    true
  }

  /**
   * SC-04. Returns false when the name is not usable. A ride already past its deadline ends automatically at the
   * deadline, keeping the name the rider gave (Android #15).
   */
  suspend fun end(name: String): Boolean = locked {
    val ride = activeRide.value ?: return@locked true
    val clean = rideName(name) ?: return@locked false
    val now = deps.now()
    if (!autoEndIfDue(ride, now, clean)) finish(ride.end(now, EndReason.MANUAL, now, clean))
    true
  }

  /**
   * The kept ride an edit starts from: the version still waiting to be written if this is that ride (its write failed
   * again just now, in flush), so that one edit does not undo the other (Android #53); otherwise the stored one.
   */
  private suspend fun keptRide(rideId: String): Ride? = unsaved?.takeIf { it.id == rideId } ?: read("ride") { deps.store.ride(rideId) }

  /**
   * SC-06 (and SC-04 through end): gives any kept ride a new name. Returns false when the name is not usable, the
   * ride is not kept, or the new name could not be written now (it is written again with the next call; Android #46).
   * The active ride stays as it is but for its name.
   */
  suspend fun rename(rideId: String, name: String): Boolean = locked {
    val clean = rideName(name) ?: return@locked false
    val now = deps.now()
    val active = activeRide.value
    if (active?.id == rideId) {
      val renamed = active.rename(clean, now)
      val saved = save(renamed)
      activeRide.value = renamed
      return@locked saved
    }
    val ride = keptRide(rideId) ?: return@locked false
    save(ride.rename(clean, now))
  }

  /**
   * SC-08 (and SC-02's 走行メモ): sets or, for null, removes any kept ride's memo. Returns false when the memo is too
   * long, the ride is not kept, or the memo could not be written now. The same memo again changes nothing. A memo
   * that could not be written is not kept to be written later: SC-08 has said it was not saved, and the rider may
   * discard it (Android #53).
   */
  suspend fun memo(rideId: String, memo: String?): Boolean = locked {
    if (!memoFits(memo)) return@locked false
    val now = deps.now()
    val waiting = unsaved
    val active = activeRide.value
    if (active?.id == rideId) {
      if (active.memo == memo) return@locked true
      val changed = active.withMemo(memo, now)
      if (!save(changed)) {
        unsaved = waiting
        return@locked false
      }
      activeRide.value = changed
      return@locked true
    }
    val ride = keptRide(rideId) ?: return@locked false
    if (ride.memo == memo) return@locked true
    if (save(ride.withMemo(memo, now))) return@locked true
    unsaved = waiting
    false
  }

  /**
   * DL-01: removes an ended ride and all that belongs to it (US-D-06.002). The ride recording or paused cannot be
   * deleted (slice 3 spec §2). A save of this ride still waiting is dropped, so it does not write the ride back.
   */
  suspend fun delete(rideId: String): Boolean = locked {
    if (activeRide.value?.id == rideId) return@locked false
    var deleted = false
    val written = write("delete") { deleted = deps.store.delete(rideId, deps.now()) }
    if (!written || !deleted) return@locked false
    // Only once the ride is gone: a deletion that failed leaves its waiting change to be written (Android #53).
    if (unsaved?.id == rideId) unsaved = null
    storeFailing.value = unsaved != null
    deps.changed()
    true
  }

  /**
   * SC-16: the longest recording time changed (US-C-08.005). The recording segment's deadline is worked out again from
   * its start; one already passed ends the ride automatically at that deadline. A paused ride takes the new time when
   * it resumes. The deadline is not sent, so updatedAt stays.
   */
  suspend fun maxRecordingChanged() = locked {
    val ride = activeRide.value?.takeIf { it.status == RideStatus.RECORDING } ?: return@locked
    val d = deps
    val moved = ride.copy(deadline = ride.segments.last().startedAt + d.maxRecordingMs())
    if (autoEndIfDue(moved, d.now())) return@locked
    save(moved)
    activeRide.value = moved
    d.alarm.set(moved.id, moved.deadline!!)
  }

  suspend fun onFix(fix: Fix) = locked {
    val ride = activeRide.value?.takeIf { it.status == RideStatus.RECORDING } ?: return@locked
    val d = deps
    val now = d.now()
    if (autoEndIfDue(ride, now)) return@locked
    // Fused may hand over a cached fix when the watch starts again: one from before this segment is not on its route.
    if (!acceptRoutePoint(fix) || fix.timestamp < ride.segments.last().startedAt) {
      write("alive") { d.store.markAlive(ride.id, now) }
      activeRide.value = ride.copy(lastAliveAt = now)
      return@locked
    }
    val point = RoutePoint(ride.segments.last().seq, fix.timestamp, fix.latitude, fix.longitude, fix.accuracy)
    val distance = ride.distanceMeters + stepMeters(lastPoint, point)
    if (write("point") { d.store.addPoint(ride.id, point, distance, now) }) d.pointAdded()
    lastPoint = point
    activeRide.value = ride.copy(distanceMeters = distance, lastAliveAt = now)
  }

  /** Called every minute while the service records: the ride is alive, and its deadline may have come. */
  suspend fun heartbeat() = locked {
    val ride = activeRide.value?.takeIf { it.status == RideStatus.RECORDING } ?: return@locked
    val now = deps.now()
    if (autoEndIfDue(ride, now)) return@locked
    write("alive") { deps.store.markAlive(ride.id, now) }
    activeRide.value = ride.copy(lastAliveAt = now)
  }

  /** The deadline alarm went off. It may come late; it never comes early. */
  suspend fun deadlineReached() = locked {
    val ride = activeRide.value?.takeIf { it.status == RideStatus.RECORDING } ?: return@locked
    autoEndIfDue(ride, deps.now())
    Unit
  }

  /** The device is shutting down (ACTION_SHUTDOWN). */
  suspend fun shutdown() = locked {
    val ride = activeRide.value ?: return@locked
    val now = deps.now()
    finish(ride.end(now, EndReason.SHUTDOWN, now))
  }

  private suspend fun autoEndIfDue(ride: Ride, now: Long, name: String = ride.name): Boolean {
    val deadline = ride.deadline ?: return false
    if (now < deadline) return false
    finish(ride.end(deadline, EndReason.AUTO, now, name))
    return true
  }

  private suspend fun finish(ended: Ride) {
    val d = deps
    save(ended)
    activeRide.value = null
    lastPoint = null
    d.alarm.cancel()
    d.recording(false)
    d.log("ride_end", mapOf("ride" to ended.id, "reason" to ended.endReason))
    if (ended.endReason == EndReason.AUTO) d.autoEnded(ended)
  }
}
