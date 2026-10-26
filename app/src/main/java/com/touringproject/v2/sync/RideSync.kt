package com.touringproject.v2.sync

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.AskMemoBody
import com.touringproject.v2.api.NoteSaved
import com.touringproject.v2.api.OfflineAskSaved
import com.touringproject.v2.api.RideApi
import com.touringproject.v2.api.RouteBody
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/** A ride that may have something left to send, with what the Backend already has of it. */
data class Unsent(val ride: Ride, val sentUpdatedAt: Long?, val rejectedUpdatedAt: Long?, val sentPoints: Int, val totalPoints: Int)

/** A record deleted on the device whose deletion has not reached the Backend (slice 3 spec §4.2). kind: ask / ride / note. */
data class PendingDeletion(val kind: String, val id: String, val rideId: String?)

/** A conversation's memo that has changed since the Backend last took it; memo null removes it. */
data class UnsentAskMemo(val askId: String, val memo: String?, val updatedAt: Long)

/** A point memo that has changed since the Backend last took it, as a whole; it goes as it is now (slice 3 spec §5.1). */
data class UnsentNote(
  val id: String,
  val rideId: String,
  val at: Long,
  val latitude: Double?,
  val longitude: Double?,
  val sampleLatitude: Double?,
  val sampleLongitude: Double?,
  val sampleAt: Long?,
  val text: String,
  val updatedAt: Long,
)

/**
 * An unreached ask that has changed since the Backend last took it (slice 4 spec §5.1). headingDegrees is the device's
 * heading, or what the Backend settled once it has taken it; the sample is left only on one kept before slice 5.
 */
data class UnsentUnreached(
  val askId: String,
  val rideId: String,
  val askedAt: Long,
  val latitude: Double?,
  val longitude: Double?,
  val sampleLatitude: Double?,
  val sampleLongitude: Double?,
  val sampleAt: Long?,
  val updatedAt: Long,
  val headingDegrees: Double? = null,
)

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
  suspend fun unsentNotes(): List<UnsentNote>
  /** The Backend took the point memo as of updatedAt; address and headingDegrees are what it settled, null when it did not say. */
  suspend fun noteSent(noteId: String, updatedAt: Long, address: String?, headingDegrees: Double?)
  /** The Backend refused the point memo as of updatedAt (400); that version is not sent again. */
  suspend fun noteRefused(noteId: String, updatedAt: Long)
  /**
   * Gives the point memos and unreached asks the Backend took without a place a new version, so they go again and the
   * Backend looks their place up again (Android #67).
   */
  suspend fun retryPlaces()
  suspend fun unsentUnreached(): List<UnsentUnreached>
  /** The Backend took the unreached ask as of updatedAt; address and headingDegrees are what it settled, null when it did not say. */
  suspend fun unreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?)
  /** The Backend refused the unreached ask as of updatedAt (400, or 409 for an id it took in); it is not sent again. */
  suspend fun unreachedRefused(askId: String, updatedAt: Long)
}

/** How the Backend answered a send that did not fail on the way. */
private enum class Reply { TAKEN, REFUSED, GONE }

/**
 * Sends rides, their routes, memos, point memos, unreached asks and deletions to the Backend (slice 2 spec §5.3, slice
 * 3 spec §5, slice 4 spec §5). A pass reads what is left to send and sends it, deletions first, so a deleted record's
 * old version is never put back in the same pass; with nothing left it makes no request at all. Unreached asks go
 * before the memos, so a memo on one finds its ask at the Backend. Passes run one at a time; the ones asked for while
 * a pass runs fold into one more. A failure other than a refusal ends the pass, and the next trigger tries again;
 * there is no timer of its own. Without a network a pass does not try at all: the network coming back is a trigger.
 * While the cloud's records are read in, no pass runs (slice 4 spec §6.3).
 */
class RideSync(
  private val store: SyncStore,
  /** The client for the current key and user name, or null while there is no key or no API URL. */
  private val api: () -> RideApi?,
  /** Whether the device has a network now (slice 4 spec §5.2). */
  private val online: () -> Boolean = { true },
  /** A pass ran to its end, nothing it tried having failed: SC-14's 最後に保存した時刻 (slice 4 spec §7.1). */
  private val saved: suspend () -> Unit = {},
  private val log: (event: String, data: Map<String, Any?>) -> Unit,
) {
  private val requests = Channel<Unit>(Channel.CONFLATED)
  private var points = 0
  private val passing = Mutex()
  private val sendingNow = MutableStateFlow(false)

  /** True while a pass talks to the Backend (SC-14's 今すぐ保存 waits for it). */
  val sending: StateFlow<Boolean> = sendingNow.asStateFlow()

  /** A trigger: something changed, the app came to the front, it started, or the key or user name changed. Safe from any thread. */
  fun request() {
    requests.trySend(Unit)
  }

  /** Asks for a pass every ROUTE_CHUNK_POINTS points recorded in this process (slice 2 spec §5.3). Main thread only. */
  fun pointAdded() {
    points += 1
    if (points % ROUTE_CHUNK_POINTS == 0) request()
  }

  /**
   * Runs the passes, one after another, until cancelled. A place the Backend could not tell is asked for again once per
   * start: often it was a lookup that failed for a while (Android #67); a place with no address at all costs one request.
   */
  suspend fun run() {
    read("retry_places") { store.retryPlaces() }
    for (request in requests) pass()
  }

  suspend fun pass() = passing.withLock {
    if (!online()) return@withLock
    val client = api() ?: return@withLock
    sendingNow.value = true
    val done = try {
      send(client)
    } finally {
      sendingNow.value = false
    }
    if (done) saved()
  }

  /**
   * Runs block with no pass under way or begun meanwhile (slice 4 spec §6.3): one under way finishes first, and the
   * triggers that come meanwhile wait until block is done.
   */
  suspend fun <T> withoutPasses(block: suspend () -> T): T = passing.withLock { block() }

  // False when something failed and the pass ended early.
  private suspend fun send(client: RideApi): Boolean {
    val deletions = read("deletions") { store.deletions() } ?: return false
    for (deletion in deletions) {
      if (!attempt(deletion.id) { sendDeletion(client, deletion) }) return false
    }
    val rides = read("unsent") { store.unsent() } ?: return false
    for (unsent in rides) {
      if (!attempt(unsent.ride.id) { sendRide(client, unsent) }) return false
    }
    val notes = read("notes") { store.unsentNotes() } ?: return false
    for (note in notes) {
      if (!attempt(note.id) { sendNote(client, note) }) return false
    }
    val unreached = read("unreached") { store.unsentUnreached() } ?: return false
    for (ask in unreached) {
      if (!attempt(ask.askId) { sendUnreached(client, ask) }) return false
    }
    val memos = read("ask_memos") { store.unsentAskMemos() } ?: return false
    for (memo in memos) {
      if (!attempt(memo.askId) { sendAskMemo(client, memo) }) return false
    }
    return true
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

  // A deletion that is refused can never go either; its mark goes, and the log tells. So does a mark the device cannot
  // send at all (a kind it does not know, a point memo without its ride): kept, it would hold back every pass (Android #54).
  private suspend fun sendDeletion(client: RideApi, deletion: PendingDeletion) {
    val what = "delete/${deletion.kind}"
    val rideId = deletion.rideId
    when {
      deletion.kind == "ride" -> reply(deletion.id, what) { client.deleteRide(deletion.id) }
      deletion.kind == "ask" -> reply(deletion.id, what) { client.deleteAsk(deletion.id) }
      deletion.kind == "note" && rideId != null -> reply(deletion.id, what) { client.deleteNote(rideId, deletion.id) }
      else -> log("sync_unsendable", mapOf("id" to deletion.id, "what" to what))
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

  // GONE: the memo or its ride was deleted on the Backend; it has no address to give.
  private suspend fun sendNote(client: RideApi, note: UnsentNote) {
    var saved: NoteSaved? = null
    if (reply(note.id, "note") { saved = client.putNote(note.rideId, note.id, note.toBody()) } == Reply.REFUSED) {
      store.noteRefused(note.id, note.updatedAt)
    } else {
      store.noteSent(note.id, note.updatedAt, saved?.address, saved?.headingDegrees)
    }
  }

  // GONE: the ask or its ride was deleted on the Backend; it has no address to give.
  private suspend fun sendUnreached(client: RideApi, ask: UnsentUnreached) {
    var saved: OfflineAskSaved? = null
    if (reply(ask.askId, "unreached") { saved = client.putOfflineAsk(ask.askId, ask.toBody()) } == Reply.REFUSED) {
      store.unreachedRefused(ask.askId, ask.updatedAt)
    } else {
      store.unreachedSent(ask.askId, ask.updatedAt, saved?.address, saved?.headingDegrees)
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
   * TAKEN when the Backend took it; REFUSED when it refused it (400, 404 for a memo of an ask it does not have, or 409
   * for an unreached ask whose id it took in) or
   * the device could not even build it (a value that cannot be written as JSON, Android #25); GONE when it was deleted
   * there (410). Refusals and GONE are logged. Other failures throw and end the pass.
   */
  private suspend fun reply(id: String, what: String, call: suspend () -> Unit): Reply = try {
    call()
    Reply.TAKEN
  } catch (e: ApiError) {
    when (e.kind) {
      ApiErrorKind.BAD_REQUEST, ApiErrorKind.NOT_FOUND, ApiErrorKind.CONFLICT -> {
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

/**
 * A trigger each time the device's network comes back (slice 4 spec §5.2): a change from none to one, not the state it
 * starts in. While the process lives, what waited for the network then goes without the app coming to the front.
 */
fun reconnections(online: Flow<Boolean>): Flow<Unit> = flow {
  var before: Boolean? = null
  online.collect { now ->
    if (before == false && now) emit(Unit)
    before = now
  }
}
