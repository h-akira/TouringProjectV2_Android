package com.touringproject.v2.sync

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.CloudDeleted
import com.touringproject.v2.api.CloudPage
import com.touringproject.v2.api.CloudRecord
import com.touringproject.v2.api.RecordsApi
import com.touringproject.v2.ride.RoutePoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private fun mark(id: String) = CloudDeleted("note", id, "r1", "2026-10-22T03:00:00Z")

/** A sync with nothing to send whose passes are counted as it asks for its client. */
private val nothingToSend = object : SyncStore {
  override suspend fun unsent() = emptyList<Unsent>()
  override suspend fun points(rideId: String, from: Int, until: Int) = emptyList<RoutePoint>()
  override suspend fun rideSent(rideId: String, updatedAt: Long) = Unit
  override suspend fun rideRefused(rideId: String, updatedAt: Long) = Unit
  override suspend fun chunkSent(rideId: String, chunk: ChunkRange, refused: Boolean) = Unit
  override suspend fun deletions() = emptyList<PendingDeletion>()
  override suspend fun deletionSent(deletion: PendingDeletion) = Unit
  override suspend fun unsentAskMemos() = emptyList<UnsentAskMemo>()
  override suspend fun askMemoSent(askId: String, updatedAt: Long) = Unit
  override suspend fun askMemoRefused(askId: String, updatedAt: Long) = Unit
  override suspend fun unsentNotes() = emptyList<UnsentNote>()
  override suspend fun noteSent(noteId: String, updatedAt: Long, address: String?, headingDegrees: Double?) = Unit
  override suspend fun noteRefused(noteId: String, updatedAt: Long) = Unit
  override suspend fun retryPlaces() = Unit
  override suspend fun unsentUnreached() = emptyList<UnsentUnreached>()
  override suspend fun unreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?) = Unit
  override suspend fun unreachedRefused(askId: String, updatedAt: Long) = Unit
}

/** runIn is where a read runs: a test cancels it apart from the test's own scope. */
private class Cloud(val scope: TestScope, val runIn: CoroutineScope = scope) {
  /** Pages by user name, then by cursor (null for the first). */
  val pages = mutableMapOf<String, Map<String?, CloudPage>>()
  val reads = mutableListOf<String>()
  /** The cursors whose page fails (null for the first page). */
  var failOn = setOf<String?>()
  var gate: CompletableDeferred<Unit>? = null
  val taken = mutableListOf<Pair<List<CloudRecord>, Boolean>>()
  var takes: TakeResult = TakeResult.Done(2)
  var failTake = false
  /** When set, a take waits for it. */
  var takeGate: CompletableDeferred<Unit>? = null
  /** The user names saved, in order; a name in failSave cannot be saved. */
  val saved = mutableListOf<String>()
  var failSave = setOf<String>()
  var switched = 0
  var passes = 0

  val store = object : TakeoverStore {
    override suspend fun take(cloud: List<CloudRecord>, replace: Boolean, now: Long): TakeResult {
      takeGate?.await()
      if (failTake) throw IOException("disk full")
      taken += cloud to replace
      return takes
    }
  }

  /** SC-18's change from rider01. A save, as DataStore's, throws when its coroutine has been cancelled. */
  fun switch(peek: Peek) = takeover.switchTo(
    peek,
    from = "rider01",
    save = {
      currentCoroutineContext().ensureActive()
      if (it in failSave) throw IOException("disk full") else saved += it
    },
    switched = { switched += 1 },
  )

  fun api(user: String) = object : RecordsApi {
    override suspend fun records(cursor: String?): CloudPage {
      reads += "$user:$cursor"
      gate?.await()
      if (cursor in failOn) throw ApiError(ApiErrorKind.NETWORK)
      return pages.getValue(user).getValue(cursor)
    }
  }

  var hasKey = true
  val sync = RideSync(nothingToSend, { passes += 1; null }) { _, _ -> }
  val takeover = Takeover({ if (hasKey) api(it) else null }, store, sync, { 1_000L }, runIn) { _, _ -> }
}

class TakeoverTest {
  @Test
  fun a_peek_tells_whether_the_name_has_records() = runTest {
    val c = Cloud(this)
    c.pages["empty"] = mapOf(null to CloudPage(emptyList(), null))
    c.pages["taro01"] = mapOf(null to CloudPage(listOf(mark("n1")), null))
    c.pages["more"] = mapOf(null to CloudPage(emptyList(), "c2"))
    assertFalse(c.takeover.peek("empty")!!.exists)
    assertTrue(c.takeover.peek("taro01")!!.exists)
    assertTrue(c.takeover.peek("more")!!.exists)
    c.failOn = setOf(null)
    assertNull(c.takeover.peek("taro01"))
    c.failOn = emptySet()
    c.hasKey = false
    assertNull(c.takeover.peek("taro01"))
  }

  // Slice 4 spec §6.4: every page is read, then the name is saved and the device's records are replaced with them
  // (the name first: Android #72).
  @Test
  fun a_switch_reads_every_page_then_saves_the_name_and_replaces_the_records() = runTest {
    val c = Cloud(this)
    c.pages["taro01"] = mapOf(null to CloudPage(listOf(mark("n1")), "c2"), "c2" to CloudPage(listOf(mark("n2")), null))
    val peek = c.takeover.peek("taro01")!!
    c.switch(peek)
    assertEquals(TakeOp.SWITCH, c.takeover.state.value.running)
    runCurrent()
    assertEquals(listOf("taro01:null", "taro01:c2"), c.reads)
    assertEquals(listOf(listOf(mark("n1"), mark("n2")) to true), c.taken)
    assertEquals(listOf("taro01"), c.saved)
    assertEquals(1, c.switched)
    assertEquals(TakeState(null, TakeOp.SWITCH, TakeResult.Done(2)), c.takeover.state.value)
  }

  @Test
  fun a_page_that_cannot_be_read_changes_nothing() = runTest {
    val c = Cloud(this)
    c.pages["taro01"] = mapOf(null to CloudPage(listOf(mark("n1")), "c2"))
    c.failOn = setOf("c2")
    c.switch(c.takeover.peek("taro01")!!)
    runCurrent()
    assertEquals(emptyList<Any>(), c.taken)
    assertEquals(emptyList<String>(), c.saved)
    assertEquals(0, c.switched)
    assertEquals(TakeResult.Failed, c.takeover.state.value.result)
  }

  // US-E-06.008: something left to send (found in the write's own transaction) puts the name back as it was.
  @Test
  fun a_blocked_switch_puts_the_name_back() = runTest {
    val c = Cloud(this)
    c.pages["taro01"] = mapOf(null to CloudPage(emptyList(), null))
    c.takes = TakeResult.Blocked(BlockReason.UNSENT)
    c.switch(c.takeover.peek("taro01")!!)
    runCurrent()
    assertEquals(listOf("taro01", "rider01"), c.saved)
    assertEquals(0, c.switched)
    assertEquals(TakeResult.Blocked(BlockReason.UNSENT), c.takeover.state.value.result)
  }

  // Android #72: a name that cannot be saved leaves the records as they were, and so does a write that fails after it.
  @Test
  fun a_name_that_cannot_be_saved_changes_nothing() = runTest {
    val c = Cloud(this)
    c.pages["taro01"] = mapOf(null to CloudPage(emptyList(), null))
    c.failSave = setOf("taro01")
    c.switch(c.takeover.peek("taro01")!!)
    runCurrent()
    assertEquals(emptyList<Any>(), c.taken)
    assertEquals(0, c.switched)
    assertEquals(TakeResult.Failed, c.takeover.state.value.result)
  }

  @Test
  fun a_write_that_fails_after_the_name_puts_the_name_back() = runTest {
    val c = Cloud(this)
    c.pages["taro01"] = mapOf(null to CloudPage(emptyList(), null))
    c.failTake = true
    c.switch(c.takeover.peek("taro01")!!)
    runCurrent()
    assertEquals(listOf("taro01", "rider01"), c.saved)
    assertEquals(0, c.switched)
    assertEquals(TakeResult.Failed, c.takeover.state.value.result)
  }

  // Android #88: a read cancelled while the records go in still puts the name back, so the two never part.
  @Test
  fun a_switch_cancelled_while_the_records_go_in_still_puts_the_name_back() = runTest {
    val reads = Job()
    val c = Cloud(this, runIn = CoroutineScope(coroutineContext + reads))
    c.pages["taro01"] = mapOf(null to CloudPage(emptyList(), null))
    c.takes = TakeResult.Blocked(BlockReason.UNSENT)
    c.takeGate = CompletableDeferred()
    c.switch(c.takeover.peek("taro01")!!)
    runCurrent()
    reads.cancel()
    c.takeGate!!.complete(Unit)
    runCurrent()
    assertEquals(listOf("taro01", "rider01"), c.saved)
  }

  // Android #88: a name that cannot be put back is told as it is: the new name is saved, the records are the old one's.
  @Test
  fun a_name_that_cannot_be_put_back_is_told_as_mismatched() = runTest {
    for (failTake in listOf(false, true)) {
      val c = Cloud(this)
      c.pages["taro01"] = mapOf(null to CloudPage(emptyList(), null))
      c.takes = TakeResult.Blocked(BlockReason.UNSENT)
      c.failTake = failTake
      c.failSave = setOf("rider01")
      c.switch(c.takeover.peek("taro01")!!)
      runCurrent()
      assertEquals(listOf("taro01"), c.saved)
      assertEquals(0, c.switched)
      assertEquals(TakeResult.Mismatched, c.takeover.state.value.result)
    }
  }

  @Test
  fun a_reread_merges_the_current_names_records() = runTest {
    val c = Cloud(this)
    c.pages["rider01"] = mapOf(null to CloudPage(listOf(mark("n1")), null))
    c.takeover.reread("rider01")
    runCurrent()
    assertEquals(listOf(listOf(mark("n1")) to false), c.taken)
    assertEquals(TakeState(null, TakeOp.REREAD, TakeResult.Done(2)), c.takeover.state.value)
  }

  // Slice 4 spec §6.3: no pass runs while the records are read in; one runs after, for what came in to be sent.
  @Test
  fun no_pass_runs_during_a_read_and_one_runs_after() = runTest {
    val c = Cloud(this)
    c.pages["rider01"] = mapOf(null to CloudPage(emptyList(), null))
    val passes = launch { c.sync.run() }
    c.gate = CompletableDeferred()
    c.takeover.reread("rider01")
    runCurrent()
    c.sync.request()
    runCurrent()
    assertEquals(0, c.passes)
    c.takeover.reread("rider01")
    c.gate!!.complete(Unit)
    runCurrent()
    // The pass asked for during the read, then the one the read asks for.
    assertEquals(2, c.passes)
    assertEquals(1, c.taken.size)
    passes.cancel()
  }

  @Test
  fun a_result_seen_goes_but_a_read_under_way_stays() = runTest {
    val c = Cloud(this)
    c.pages["rider01"] = mapOf(null to CloudPage(emptyList(), null))
    c.gate = CompletableDeferred()
    c.takeover.reread("rider01")
    c.takeover.seen()
    assertEquals(TakeOp.REREAD, c.takeover.state.value.running)
    c.gate!!.complete(Unit)
    runCurrent()
    assertEquals(TakeResult.Done(2), c.takeover.state.value.result)
    c.takeover.seen()
    assertEquals(TakeState(), c.takeover.state.value)
  }

  // Android #74: a second read on the same screen does not show the first one's result while it reads.
  @Test
  fun a_new_read_starts_without_the_last_result() = runTest {
    val c = Cloud(this)
    c.pages["rider01"] = mapOf(null to CloudPage(emptyList(), null))
    c.takeover.reread("rider01")
    runCurrent()
    c.gate = CompletableDeferred()
    c.takeover.reread("rider01")
    assertEquals(TakeState(running = TakeOp.REREAD), c.takeover.state.value)
    c.gate!!.complete(Unit)
    runCurrent()
  }
}
