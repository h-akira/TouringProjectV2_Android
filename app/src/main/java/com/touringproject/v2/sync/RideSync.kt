package com.touringproject.v2.sync

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.RideApi
import com.touringproject.v2.api.RouteBody
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/** A ride that may have something left to send, with what the Backend already has of it. */
data class Unsent(val ride: Ride, val sentUpdatedAt: Long?, val rejectedUpdatedAt: Long?, val sentPoints: Int, val totalPoints: Int)

interface SyncStore {
  /** Rides whose meta has changed since it was sent (and was not refused), or that have points not yet sent. */
  suspend fun unsent(): List<Unsent>
  /** The ride's points from index from to until, in the order they were recorded. */
  suspend fun points(rideId: String, from: Int, until: Int): List<RoutePoint>
  /** The Backend took the ride as of updatedAt. */
  suspend fun rideSent(rideId: String, updatedAt: Long)
  /** The Backend refused the ride as of updatedAt (400); that version is not sent again. */
  suspend fun rideRefused(rideId: String, updatedAt: Long)
  /** The chunk's points have gone; refused when the Backend refused them (400), so they are not sent again either. */
  suspend fun chunkSent(rideId: String, chunk: ChunkRange, refused: Boolean)
}

/**
 * Sends rides and their routes to the Backend (slice 2 spec §5.3). A pass reads what is left to send and sends it; with
 * nothing left it makes no request at all. Passes run one at a time; the ones asked for while a pass runs fold into one
 * more. A failure other than a refusal ends the pass, and the next trigger tries again; there is no timer of its own.
 */
class RideSync(
  private val store: SyncStore,
  /** The client for the current key and user name, or null while there is no key or no API URL. */
  private val api: () -> RideApi?,
  private val log: (event: String, data: Map<String, Any?>) -> Unit,
) {
  private val requests = Channel<Unit>(Channel.CONFLATED)
  private var points = 0

  /** A trigger: the ride changed, the app came to the front, or it started. Safe from any thread. */
  fun request() {
    requests.trySend(Unit)
  }

  /** Asks for a pass every ROUTE_CHUNK_POINTS points recorded in this process (slice 2 spec §5.3). Main thread only. */
  fun pointAdded() {
    points += 1
    if (points % ROUTE_CHUNK_POINTS == 0) request()
  }

  /** Runs the passes, one after another, until cancelled. */
  suspend fun run() {
    for (request in requests) pass()
  }

  suspend fun pass() {
    val client = api() ?: return
    val rides = try {
      store.unsent()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("sync_failed", mapOf("op" to "unsent", "error" to e.toString()))
      return
    }
    for (unsent in rides) {
      if (!send(client, unsent)) return
    }
  }

  // False ends the pass: the network, the key or the database failed, and the next trigger tries again.
  private suspend fun send(client: RideApi, unsent: Unsent): Boolean {
    val ride = unsent.ride
    try {
      if (metaToSend(ride.updatedAt, unsent.sentUpdatedAt, unsent.rejectedUpdatedAt)) {
        if (accepted(ride.id, "ride") { client.putRide(ride.id, ride.toBody()) }) {
          store.rideSent(ride.id, ride.updatedAt)
        } else {
          store.rideRefused(ride.id, ride.updatedAt)
        }
      }
      for (chunk in chunksToSend(unsent.sentPoints, unsent.totalPoints, ride.status != RideStatus.RECORDING)) {
        val points = store.points(ride.id, chunk.from, chunk.until)
        val ok = accepted(ride.id, "route/${chunk.seq}") { client.putRoute(ride.id, chunk.seq, RouteBody(points.map { it.toBody() })) }
        store.chunkSent(ride.id, chunk, refused = !ok)
      }
      return true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("sync_failed", mapOf("ride" to ride.id, "error" to e.toString()))
      return false
    }
  }

  // True when the Backend took it, false when it refused it (400: logged, never sent again). Other failures throw.
  private suspend fun accepted(rideId: String, what: String, put: suspend () -> Unit): Boolean = try {
    put()
    true
  } catch (e: ApiError) {
    if (e.kind != ApiErrorKind.BAD_REQUEST) throw e
    log("sync_refused", mapOf("ride" to rideId, "what" to what, "error" to e.message))
    false
  }
}
