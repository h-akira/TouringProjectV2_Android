package com.touringproject.v2.sync

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.AskMemoBody
import com.touringproject.v2.api.RideApi
import com.touringproject.v2.api.RouteBody
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

/** A ride that may have something left to send, with what the Backend already has of it. */
data class Unsent(val ride: Ride, val sentUpdatedAt: Long?, val rejectedUpdatedAt: Long?, val sentPoints: Int, val totalPoints: Int)

/** A record deleted on the device whose deletion has not reached the Backend (slice 3 spec §4.2). kind: ask / ride / note. */
data class PendingDeletion(val kind: String, val id: String, val rideId: String?)

/** A conversation's memo that has changed since the Backend last took it; memo null removes it. */
data class UnsentAskMemo(val askId: String, val memo: String?, val updatedAt: Long)

interface SyncStore {
  /**
   * Rides whose meta has changed since it was sent (and was not refused), or that have points not yet sent. Read in
   * one transaction, so a ride and its segments are always of the same version (Android #23).
   */
  suspend fun unsent(): List<Unsent>
  /** The ride's points from index from to until, in the order they were recorded. */
  suspend fun points(rideId: String, from: Int, until: Int): List<RoutePoint>
  /** The Backend took the ride as of updatedAt. */
  suspend fun rideSent(rideId: String, updatedAt: Long)
  /** The Backend refused the ride as of updatedAt (400); that version is not sent again. */
  suspend fun rideRefused(rideId: String, updatedAt: Long)
  /** The chunk's points have gone; refused when the Backend refused them (400), so they are not sent again either. */
  suspend fun chunkSent(rideId: String, chunk: ChunkRange, refused: Boolean)
  suspend fun deletions(): List<PendingDeletion>
  /** The deletion reached the Backend (or can never be sent): its mark goes. */
  suspend fun deletionSent(deletion: PendingDeletion)
  suspend fun unsentAskMemos(): List<UnsentAskMemo>
  suspend fun askMemoSent(askId: String, updatedAt: Long)
  /** The Backend refused the memo as of updatedAt (400, or 404 for an ask it does not have); it is not sent again. */
  suspend fun askMemoRefused(askId: String, updatedAt: Long)
}

/** How the Backend answered a send that did not fail on the way. */
private enum class Reply { TAKEN, REFUSED, GONE }

/**
 * Sends rides, their routes, memos and deletions to the Backend (slice 2 spec §5.3, slice 3 spec §5). A pass reads
 * what is left to send and sends it, deletions first, so a deleted record's old version is never put back in the same
 * pass; with nothing left it makes no request at all. Passes run one at a time; the ones asked for while a pass runs
 * fold into one more. A failure other than a refusal ends the pass, and the next trigger tries again; there is no
 * timer of its own.
 */
class RideSync(
  private val store: SyncStore,
  /** The client for the current key and user name, or null while there is no key or no API URL. */
  private val api: () -> RideApi?,
  private val log: (event: String, data: Map<String, Any?>) -> Unit,
) {
  private val requests = Channel<Unit>(Channel.CONFLATED)
  private var points = 0

  /** A trigger: something changed, the app came to the front, it started, or the key or user name changed. Safe from any thread. */
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
    val deletions = read("deletions") { store.deletions() } ?: return
    for (deletion in deletions) {
      if (!attempt(deletion.id) { sendDeletion(client, deletion) }) return
    }
    val rides = read("unsent") { store.unsent() } ?: return
    for (unsent in rides) {
      if (!attempt(unsent.ride.id) { sendRide(client, unsent) }) return
    }
    val memos = read("ask_memos") { store.unsentAskMemos() } ?: return
    for (memo in memos) {
      if (!attempt(memo.askId) { sendAskMemo(client, memo) }) return
    }
  }

  private suspend fun <T> read(op: String, block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log("sync_failed", mapOf("op" to op, "error" to e.toString()))
    null
  }

  // False ends the pass: the network, the key or the database failed, and the next trigger tries again.
  private suspend fun attempt(id: String, send: suspend () -> Unit): Boolean = try {
    send()
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log("sync_failed", mapOf("id" to id, "error" to e.toString()))
    false
  }

  // A deletion that is refused can never go either; its mark goes, and the log tells.
  private suspend fun sendDeletion(client: RideApi, deletion: PendingDeletion) {
    reply(deletion.id, "delete/${deletion.kind}") {
      when (deletion.kind) {
        "ride" -> client.deleteRide(deletion.id)
        "ask" -> client.deleteAsk(deletion.id)
        else -> throw IllegalStateException("unknown deletion kind ${deletion.kind}")
      }
    }
    store.deletionSent(deletion)
  }

  private suspend fun sendRide(client: RideApi, unsent: Unsent) {
    val ride = unsent.ride
    if (metaToSend(ride.updatedAt, unsent.sentUpdatedAt, unsent.rejectedUpdatedAt)) {
      // GONE: the ride was deleted on the Backend; the device has nothing left to tell it (slice 3 spec §5.2).
      if (reply(ride.id, "ride") { client.putRide(ride.id, ride.toBody()) } == Reply.REFUSED) {
        store.rideRefused(ride.id, ride.updatedAt)
      } else {
        store.rideSent(ride.id, ride.updatedAt)
      }
    }
    for (chunk in chunksToSend(unsent.sentPoints, unsent.totalPoints, ride.status != RideStatus.RECORDING)) {
      val points = store.points(ride.id, chunk.from, chunk.until)
      val answer = reply(ride.id, "route/${chunk.seq}") { client.putRoute(ride.id, chunk.seq, RouteBody(points.map { it.toBody() })) }
      store.chunkSent(ride.id, chunk, refused = answer == Reply.REFUSED)
    }
  }

  private suspend fun sendAskMemo(client: RideApi, memo: UnsentAskMemo) {
    if (reply(memo.askId, "memo") { client.putAskMemo(memo.askId, AskMemoBody(memo.memo, iso(memo.updatedAt))) } == Reply.REFUSED) {
      store.askMemoRefused(memo.askId, memo.updatedAt)
    } else {
      store.askMemoSent(memo.askId, memo.updatedAt)
    }
  }

  /**
   * TAKEN when the Backend took it; REFUSED when it refused it (400, or 404 for a memo of an ask it does not have) or
   * the device could not even build it (a value that cannot be written as JSON, Android #25); GONE when it was deleted
   * there (410). Refusals and GONE are logged. Other failures throw and end the pass.
   */
  private suspend fun reply(id: String, what: String, call: suspend () -> Unit): Reply = try {
    call()
    Reply.TAKEN
  } catch (e: ApiError) {
    when (e.kind) {
      ApiErrorKind.BAD_REQUEST, ApiErrorKind.NOT_FOUND -> {
        log("sync_refused", mapOf("id" to id, "what" to what, "error" to e.message))
        Reply.REFUSED
      }
      ApiErrorKind.GONE -> {
        log("sync_gone", mapOf("id" to id, "what" to what))
        Reply.GONE
      }
      else -> throw e
    }
  } catch (e: SerializationException) {
    log("sync_unsendable", mapOf("id" to id, "what" to what, "error" to e.toString()))
    Reply.REFUSED
  }
}

/**
 * A trigger each time the key or the user name changes to a pair that can send (Android #29), the key entered for the
 * first time included. Collect it once both are loaded: the values then are not a change (the start has its own
 * trigger). A key removed is no trigger.
 */
fun credentialChanges(key: Flow<String?>, userName: Flow<String?>): Flow<Unit> =
  combine(key, userName) { k, u -> k to u }
    .distinctUntilChanged()
    .drop(1)
    .filter { (k, u) -> k != null && u != null }
    .map { }
