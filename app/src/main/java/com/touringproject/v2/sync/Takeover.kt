package com.touringproject.v2.sync

import com.touringproject.v2.api.CloudPage
import com.touringproject.v2.api.CloudRecord
import com.touringproject.v2.api.RecordsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Reading the user's records back from the cloud (slice 4 spec §6, adr/0010): when the user name changes, and when SC-14
// reads them again.

interface TakeoverStore {
  /**
   * Merges the cloud's records into the device's (slice 4 spec §6.1) in one transaction; with replace, all the device
   * keeps goes first (§6.4: another user name). Returns Done with how many records came in or went, or Blocked when
   * nothing was done because a ride is recording or paused or, with replace, something is left to send (US-E-06.008).
   * A failure throws and changes nothing.
   */
  suspend fun take(cloud: List<CloudRecord>, replace: Boolean, now: Long): TakeResult
}

/** Why a read of the cloud changed nothing (Android #73). */
enum class BlockReason {
  /** A ride is recording or paused. */
  RIDE,

  /** Something is left to send (a change of user name only). */
  UNSENT,
}

/** What a read of the cloud came to. */
sealed interface TakeResult {
  /** The records are in: count came in or went. A user name changed is saved. */
  data class Done(val count: Int) : TakeResult

  /** Nothing changed, for reason. */
  data class Blocked(val reason: BlockReason) : TakeResult

  /** Nothing changed: the cloud could not be read whole, or what it said could not be written. */
  data object Failed : TakeResult
}

enum class TakeOp { SWITCH, REREAD }

/** running is the read under way; last and result the one that ended, for the screen that started it to tell. */
data class TakeState(val running: TakeOp? = null, val last: TakeOp? = null, val result: TakeResult? = null)

/** The first page for a user name, read before DL-03 asks (slice 4 spec §6.4); exists picks which of its texts. */
data class Peek(val userName: String, val first: CloudPage) {
  val exists: Boolean get() = first.items.isNotEmpty() || first.cursor != null
}

/**
 * Reads the cloud's records in. Every page is read before anything is written, and no pass of the sync runs from the
 * first read to the last write, so what came in and its sent markers never part (slice 4 spec §6.3). A read runs in
 * scope, so it goes on when its screen goes away (§7.2); one at a time.
 */
class Takeover(
  /** The client for a user name with the current key, or null while there is no key or no API URL. */
  private val api: (userName: String) -> RecordsApi?,
  private val store: TakeoverStore,
  private val sync: RideSync,
  private val now: () -> Long,
  private val scope: CoroutineScope,
  private val log: (event: String, data: Map<String, Any?>) -> Unit,
) {
  private val stateNow = MutableStateFlow(TakeState())
  val state: StateFlow<TakeState> = stateNow.asStateFlow()

  /** The screen that told the last result has gone, so the next visit starts without it; a read under way is kept. */
  fun seen() {
    stateNow.update { if (it.running == null) TakeState() else it }
  }

  /** null when the page could not be read. */
  suspend fun peek(userName: String): Peek? = try {
    api(userName)?.let { Peek(userName, it.records(null)) }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log("takeover_failed", mapOf("op" to "peek", "error" to e.toString()))
    null
  }

  /**
   * SC-18 after DL-03: the device's records go and the name's come in (§6.4). The name and the records cannot be
   * written in one transaction, so the name is saved first (save throws when it cannot be) and put back to from when
   * the records do not come in; nothing changes when the read, the name or the write fails (Android #72). No pass of
   * the sync runs meanwhile, so nothing goes out under the new name before its records are in. switched follows a
   * change that is done.
   */
  fun switchTo(peek: Peek, from: String, save: suspend (userName: String) -> Unit, switched: suspend () -> Unit) =
    start(TakeOp.SWITCH) {
      val records = readAll(peek.userName, peek.first)
      save(peek.userName)
      val result = try {
        store.take(records, replace = true, now())
      } catch (e: Exception) {
        putBack(from, save)
        throw e
      }
      if (result !is TakeResult.Done) {
        putBack(from, save)
        return@start result
      }
      switched()
      result
    }

  // A name that cannot be put back is logged: the records are the old name's, the saved name the new one.
  private suspend fun putBack(from: String, save: suspend (userName: String) -> Unit) {
    try {
      save(from)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("takeover_failed", mapOf("op" to "put_back", "error" to e.toString()))
      throw e
    }
  }

  /** SC-14's クラウドから読み直す: the current name's records, merged with the device's. */
  fun reread(userName: String) = start(TakeOp.REREAD) {
    store.take(readAll(userName, null), replace = false, now())
  }

  private fun start(op: TakeOp, block: suspend () -> TakeResult) {
    if (stateNow.value.running != null) return
    // The last read's result goes, so the screen does not tell it while this one reads (Android #74).
    stateNow.value = TakeState(running = op)
    scope.launch {
      val result = try {
        sync.withoutPasses { block() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("takeover_failed", mapOf("op" to op.name, "error" to e.toString()))
        TakeResult.Failed
      }
      log("takeover_done", mapOf("op" to op.name, "result" to result.toString()))
      stateNow.value = TakeState(running = null, last = op, result = result)
      // What came in to be sent (a ride that ended here) goes now.
      sync.request()
    }
  }

  private suspend fun readAll(userName: String, first: CloudPage?): List<CloudRecord> {
    val client = api(userName) ?: throw IllegalStateException("no key or API URL")
    var page = first ?: client.records(null)
    val items = page.items.toMutableList()
    while (true) {
      val cursor = page.cursor ?: return items
      page = client.records(cursor)
      items += page.items
    }
  }
}
