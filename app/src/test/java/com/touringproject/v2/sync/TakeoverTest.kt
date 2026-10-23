package com.touringproject.v2.sync

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.CloudDeleted
import com.touringproject.v2.api.CloudPage
import com.touringproject.v2.api.CloudRecord
import com.touringproject.v2.api.RecordsApi
import com.touringproject.v2.ride.RoutePoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
  override suspend fun unsentUnreached() = emptyList<UnsentUnreached>()
  override suspend fun unreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?) = Unit
  override suspend fun unreachedRefused(askId: String, updatedAt: Long) = Unit
}

private class Cloud(val scope: TestScope) {
  /** Pages by user name, then by cursor (null for the first). */
  val pages = mutableMapOf<String, Map<String?, CloudPage>>()
  val reads = mutableListOf<String>()
  /** The cursors whose page fails (null for the first page). */
  var failOn = setOf<String?>()
  var gate: CompletableDeferred<Unit>? = null
  val taken = mutableListOf<Pair<List<CloudRecord>, Boolean>>()
  var takes: Int? = 2
  val switched = mutableListOf<String>()
  var passes = 0

  val store = object : TakeoverStore {
    override suspend fun take(cloud: List<CloudRecord>, replace: Boolean, now: Long): Int? {
      taken += cloud to replace
      return takes
    }
  }

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
  val takeover = Takeover({ if (hasKey) api(it) else null }, store, sync, { 1_000L }, scope) { _, _ -> }
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

  // Slice 4 spec §6.4: every page is read, the device's records are replaced with them, and then the name is saved.
  @Test
  fun a_switch_reads_every_page_then_replaces_the_records_and_saves_the_name() = runTest {
    val c = Cloud(this)
    c.pages["taro01"] = mapOf(null to CloudPage(listOf(mark("n1")), "c2"), "c2" to CloudPage(listOf(mark("n2")), null))
    val peek = c.takeover.peek("taro01")!!
    c.takeover.switchTo(peek) { c.switched += it }
    assertEquals(TakeOp.SWITCH, c.takeover.state.value.running)
    runCurrent()
    assertEquals(listOf("taro01:null", "taro01:c2"), c.reads)
    assertEquals(listOf(listOf(mark("n1"), mark("n2")) to true), c.taken)
    assertEquals(listOf("taro01"), c.switched)
    assertEquals(TakeState(null, TakeOp.SWITCH, TakeResult.Done(2)), c.takeover.state.value)
  }

  @Test
  fun a_page_that_cannot_be_read_changes_nothing() = runTest {
    val c = Cloud(this)
    c.pages["taro01"] = mapOf(null to CloudPage(listOf(mark("n1")), "c2"))
    c.failOn = setOf("c2")
    c.takeover.switchTo(c.takeover.peek("taro01")!!) { c.switched += it }
    runCurrent()
    assertEquals(emptyList<Any>(), c.taken)
    assertEquals(emptyList<String>(), c.switched)
    assertEquals(TakeResult.Failed, c.takeover.state.value.result)
  }

  // US-E-06.008: something left to send (found in the write's own transaction) keeps the name as it was.
  @Test
  fun a_blocked_switch_keeps_the_name() = runTest {
    val c = Cloud(this)
    c.pages["taro01"] = mapOf(null to CloudPage(emptyList(), null))
    c.takes = null
    c.takeover.switchTo(c.takeover.peek("taro01")!!) { c.switched += it }
    runCurrent()
    assertEquals(emptyList<String>(), c.switched)
    assertEquals(TakeResult.Blocked, c.takeover.state.value.result)
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
}
