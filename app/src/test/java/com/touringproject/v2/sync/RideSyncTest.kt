package com.touringproject.v2.sync

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.AskMemoBody
import com.touringproject.v2.api.NoteBody
import com.touringproject.v2.api.NoteSaved
import com.touringproject.v2.api.OfflineAskBody
import com.touringproject.v2.api.OfflineAskSaved
import com.touringproject.v2.api.RideApi
import com.touringproject.v2.api.RideBody
import com.touringproject.v2.api.RouteBody
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.Ride
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.pause
import com.touringproject.v2.ride.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAX = 24 * HOUR_MS

/** The device's database as the sync sees it, and a Backend that records what it was sent. */
private class World {
  class Row(var ride: Ride, var sent: Long? = null, var rejected: Long? = null, val chunks: MutableList<Int?> = mutableListOf())
  val rows = linkedMapOf<String, Row>()
  val calls = mutableListOf<String>()
  /** Fails the call whose name (e.g. "route/r1/1") it is given, with the given kind. */
  val fail = mutableMapOf<String, ApiErrorKind>()
  /** Throws the given exception from the call whose name it is given, before anything is sent (Android #25). */
  val unbuildable = mutableMapOf<String, Exception>()
  val deletions = mutableListOf<PendingDeletion>()
  /** askId to (memo, updatedAt, sent, refused). */
  val memos = linkedMapOf<String, MemoRow>()
  class MemoRow(val memo: String?, val updatedAt: Long, var sent: Long? = null, var refused: Long? = null)
  class NoteRow(val note: UnsentNote, var sent: Long? = null, var refused: Long? = null, var address: String? = null, var heading: Double? = null)
  val notes = linkedMapOf<String, NoteRow>()
  /** What the Backend answers for a point memo, by noteId. */
  val saved = mutableMapOf<String, NoteSaved>()

  fun note(id: String, updatedAt: Long, text: String = "展望", rideId: String = "r1") {
    notes[id] = NoteRow(UnsentNote(id, rideId, 1_500, 35.0, 139.0, null, null, null, text, updatedAt))
  }
  class UnreachedRow(val ask: UnsentUnreached, var sent: Long? = null, var refused: Long? = null, var address: String? = null)
  val unreached = linkedMapOf<String, UnreachedRow>()

  fun unreached(id: String, updatedAt: Long) {
    unreached[id] = UnreachedRow(UnsentUnreached(id, "r1", 1_400, 35.0, 139.0, null, null, null, updatedAt))
  }
  var online = true
  var hasKey = true
  var gate: CompletableDeferred<Unit>? = null
  var passes = 0

  fun add(ride: Ride, points: Int = 0) {
    rows.getOrPut(ride.id) { Row(ride) }.also { it.ride = ride; repeat(points) { _ -> it.chunks += null } }
  }

  val store = object : SyncStore {
    override suspend fun unsent(): List<Unsent> {
      passes += 1
      gate?.await()
      return rows.values
        .filter { metaToSend(it.ride.updatedAt, it.sent, it.rejected) || it.chunks.any { c -> c == null } }
        .map { Unsent(it.ride, it.sent, it.rejected, it.chunks.count { c -> c != null }, it.chunks.size) }
    }
    override suspend fun points(rideId: String, from: Int, until: Int) =
      (from until until).map { RoutePoint(0, it.toLong(), 35.0, 139.0, 5.0) }
    override suspend fun rideSent(rideId: String, updatedAt: Long) { rows.getValue(rideId).sent = updatedAt }
    override suspend fun rideRefused(rideId: String, updatedAt: Long) { rows.getValue(rideId).rejected = updatedAt }
    override suspend fun chunkSent(rideId: String, chunk: ChunkRange, refused: Boolean) {
      val chunks = rows.getValue(rideId).chunks
      for (i in chunk.from until chunk.until) chunks[i] = chunk.seq
      if (refused) calls += "refused:route/$rideId/${chunk.seq}"
    }
    override suspend fun deletions() = deletions.toList()
    override suspend fun deletionSent(deletion: PendingDeletion) {
      deletions.remove(deletion)
    }
    override suspend fun unsentAskMemos() = memos.filter { (_, m) -> (m.sent ?: -1) < m.updatedAt && m.refused != m.updatedAt }
      .map { (id, m) -> UnsentAskMemo(id, m.memo, m.updatedAt) }
    override suspend fun askMemoSent(askId: String, updatedAt: Long) { memos.getValue(askId).sent = updatedAt }
    override suspend fun askMemoRefused(askId: String, updatedAt: Long) {
      memos.getValue(askId).refused = updatedAt
      calls += "refused:memo/$askId"
    }
    override suspend fun unsentNotes() = notes.values.filter { (it.sent ?: -1) < it.note.updatedAt && it.refused != it.note.updatedAt }.map { it.note }
    override suspend fun noteSent(noteId: String, updatedAt: Long, address: String?, headingDegrees: Double?) {
      notes.getValue(noteId).also { it.sent = updatedAt; it.address = address; it.heading = headingDegrees }
    }
    override suspend fun noteRefused(noteId: String, updatedAt: Long) {
      notes.getValue(noteId).refused = updatedAt
      calls += "refused:note/$noteId"
    }
    override suspend fun unsentUnreached() =
      unreached.values.filter { (it.sent ?: -1) < it.ask.updatedAt && it.refused != it.ask.updatedAt }.map { it.ask }
    override suspend fun unreachedSent(askId: String, updatedAt: Long, address: String?, headingDegrees: Double?) {
      unreached.getValue(askId).also { it.sent = updatedAt; it.address = address }
    }
    override suspend fun unreachedRefused(askId: String, updatedAt: Long) {
      unreached.getValue(askId).refused = updatedAt
      calls += "refused:unreached/$askId"
    }
  }

  private fun call(name: String) {
    unbuildable[name]?.let { throw it }
    calls += name
    fail[name]?.let { throw ApiError(it) }
  }

  val api = object : RideApi {
    override suspend fun putRide(rideId: String, body: RideBody) = call("ride/$rideId:${body.status}")
    override suspend fun putRoute(rideId: String, seq: Int, body: RouteBody) = call("route/$rideId/$seq:${body.points.size}")
    override suspend fun putAskMemo(askId: String, body: AskMemoBody) = call("memo/$askId:${body.memo}")
    override suspend fun deleteRide(rideId: String) = call("delete/ride/$rideId")
    override suspend fun deleteAsk(askId: String) = call("delete/ask/$askId")
    override suspend fun putNote(rideId: String, noteId: String, body: NoteBody): NoteSaved {
      call("note/$rideId/$noteId:${body.text}")
      return saved[noteId] ?: NoteSaved()
    }
    override suspend fun deleteNote(rideId: String, noteId: String) = call("delete/note/$rideId/$noteId")
    override suspend fun putOfflineAsk(askId: String, body: OfflineAskBody): OfflineAskSaved {
      call("unreached/$askId:${body.rideId}")
      return OfflineAskSaved("箱根町仙石原", 12.5)
    }
  }

  var saves = 0
  val sync = RideSync(store, { if (hasKey) api else null }, { online }, saved = { saves += 1 }) { _, _ -> }
}

class RideSyncTest {
  private val recording = newRide("r1", "箱根", 1_000, 7, MAX)

  @Test
  fun with_nothing_new_a_pass_makes_no_request() = runTest {
    val w = World()
    w.add(recording, points = 120)
    w.rows.getValue("r1").sent = recording.updatedAt
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  @Test
  fun a_recording_ride_sends_its_meta_and_its_full_chunks_only() = runTest {
    val w = World()
    w.add(recording, points = 650)
    w.sync.pass()
    assertEquals(listOf("ride/r1:recording", "route/r1/0:300", "route/r1/1:300"), w.calls)
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  @Test
  fun a_pause_sends_the_partial_chunk_and_it_goes_again_once_it_is_full() = runTest {
    val w = World()
    val paused = recording.pause(5_000)
    w.add(paused, points = 120)
    w.sync.pass()
    assertEquals(listOf("ride/r1:paused", "route/r1/0:120"), w.calls)
    w.calls.clear()
    w.add(paused.resume(9_000, 7, MAX), points = 230)
    w.sync.pass()
    assertEquals(listOf("ride/r1:recording", "route/r1/0:300"), w.calls)
  }

  @Test
  fun an_ended_ride_sends_all_that_is_left() = runTest {
    val w = World()
    w.add(recording.end(5_000, EndReason.MANUAL, 5_000), points = 350)
    w.sync.pass()
    assertEquals(listOf("ride/r1:ended", "route/r1/0:300", "route/r1/1:50"), w.calls)
  }

  @Test
  fun a_failure_ends_the_pass_and_the_next_one_sends_what_is_left() = runTest {
    val w = World()
    w.add(recording.end(5_000, EndReason.MANUAL, 5_000), points = 350)
    w.add(newRide("r2", "御殿場", 6_000, 7, MAX))
    w.fail["route/r1/0:300"] = ApiErrorKind.NETWORK
    w.sync.pass()
    assertEquals(listOf("ride/r1:ended", "route/r1/0:300"), w.calls)
    w.fail.clear()
    w.calls.clear()
    w.sync.pass()
    assertEquals(listOf("route/r1/0:300", "route/r1/1:50", "ride/r2:recording"), w.calls)
  }

  @Test
  fun a_refused_ride_is_not_sent_again_until_it_changes() = runTest {
    val w = World()
    val paused = recording.pause(5_000)
    w.add(paused)
    w.fail["ride/r1:paused"] = ApiErrorKind.BAD_REQUEST
    w.sync.pass()
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
    w.fail.clear()
    w.add(paused.resume(9_000, 7, MAX))
    w.sync.pass()
    assertEquals(listOf("ride/r1:recording"), w.calls)
  }

  @Test
  fun a_refused_chunk_is_marked_and_the_next_chunks_still_go() = runTest {
    val w = World()
    w.add(recording.end(5_000, EndReason.MANUAL, 5_000), points = 350)
    w.fail["route/r1/0:300"] = ApiErrorKind.BAD_REQUEST
    w.sync.pass()
    assertEquals(listOf("ride/r1:ended", "route/r1/0:300", "refused:route/r1/0", "route/r1/1:50"), w.calls)
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  @Test
  fun without_a_key_nothing_is_read_or_sent() = runTest {
    val w = World()
    w.hasKey = false
    w.add(recording, points = 300)
    w.sync.pass()
    assertEquals(0, w.passes)
    assertEquals(emptyList<String>(), w.calls)
  }

  @Test
  fun triggers_during_a_pass_fold_into_one_more_pass() = runTest {
    val w = World()
    w.gate = CompletableDeferred()
    val job = launch { w.sync.run() }
    w.sync.request()
    runCurrent()
    repeat(3) { w.sync.request() }
    w.gate!!.complete(Unit)
    runCurrent()
    assertEquals(2, w.passes)
    job.cancel()
  }

  @Test
  fun every_300th_point_asks_for_a_pass() = runTest {
    val w = World()
    val job = launch { w.sync.run() }
    repeat(299) { w.sync.pointAdded() }
    runCurrent()
    assertEquals(0, w.passes)
    w.sync.pointAdded()
    runCurrent()
    assertEquals(1, w.passes)
    assertTrue(w.calls.isEmpty())
    job.cancel()
  }

  // Slice 3 spec §5.1: deletions go first, then rides, then point memos, then memos.
  @Test
  fun a_pass_sends_deletions_then_rides_then_point_memos_then_memos() = runTest {
    val w = World()
    w.add(recording)
    w.memos["a1"] = World.MemoRow("また来たい", 2_000)
    w.note("n1", 1_900)
    w.deletions += PendingDeletion("ride", "r0", null)
    w.deletions += PendingDeletion("ask", "a0", "r1")
    w.deletions += PendingDeletion("note", "n0", "r1")
    w.sync.pass()
    assertEquals(
      listOf("delete/ride/r0", "delete/ask/a0", "delete/note/r1/n0", "ride/r1:recording", "note/r1/n1:展望", "memo/a1:また来たい"),
      w.calls,
    )
    assertTrue(w.deletions.isEmpty())
    assertEquals(2_000L, w.memos.getValue("a1").sent)
    assertEquals(1_900L, w.notes.getValue("n1").sent)
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  // A failed deletion stays and goes first in the next pass, so the deleted record is never put back before it.
  @Test
  fun a_deletion_that_fails_ends_the_pass_and_goes_again_next_time() = runTest {
    val w = World()
    w.add(recording)
    w.deletions += PendingDeletion("ask", "a0", "r1")
    w.fail["delete/ask/a0"] = ApiErrorKind.NETWORK
    w.sync.pass()
    assertEquals(listOf("delete/ask/a0"), w.calls)
    assertEquals(1, w.deletions.size)
    w.fail.clear()
    w.sync.pass()
    assertEquals(listOf("delete/ask/a0", "delete/ask/a0", "ride/r1:recording"), w.calls)
    assertTrue(w.deletions.isEmpty())
  }

  // Slice 3 spec §5.2: 410 means the record (or its ride) was deleted on the Backend; it is not sent again.
  @Test
  fun what_the_backend_has_deleted_is_taken_as_sent() = runTest {
    val w = World()
    val ended = recording.end(5_000, EndReason.MANUAL, 5_000)
    w.add(ended, points = 2)
    w.memos["a1"] = World.MemoRow("x", 2_000)
    w.fail["ride/r1:ended"] = ApiErrorKind.GONE
    w.fail["route/r1/0:2"] = ApiErrorKind.GONE
    w.fail["memo/a1:x"] = ApiErrorKind.GONE
    w.sync.pass()
    val row = w.rows.getValue("r1")
    assertEquals(ended.updatedAt, row.sent)
    assertEquals(null, row.rejected)
    assertEquals(listOf(0, 0), row.chunks)
    assertEquals(2_000L, w.memos.getValue("a1").sent)
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  // Slice 3 spec §5.2: a memo of an ask the Backend does not have (404), or one it refused (400), is not sent again.
  @Test
  fun a_memo_refused_or_of_an_unknown_ask_is_not_sent_again_until_it_changes() = runTest {
    val w = World()
    w.memos["a1"] = World.MemoRow("x", 2_000)
    w.memos["a2"] = World.MemoRow("y", 2_100)
    w.fail["memo/a1:x"] = ApiErrorKind.NOT_FOUND
    w.fail["memo/a2:y"] = ApiErrorKind.BAD_REQUEST
    w.sync.pass()
    assertEquals(listOf("memo/a1:x", "refused:memo/a1", "memo/a2:y", "refused:memo/a2"), w.calls)
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  // Android #25: a ride the device cannot even build is set aside, and the rides after it still go.
  @Test
  fun a_ride_that_cannot_be_built_does_not_hold_back_the_others() = runTest {
    val w = World()
    w.add(recording)
    w.add(newRide("r2", "御殿場", 2_000, 7, MAX))
    w.unbuildable["ride/r1:recording"] = kotlinx.serialization.SerializationException("NaN is not a valid double")
    w.sync.pass()
    assertEquals(listOf("ride/r2:recording"), w.calls)
    assertEquals(recording.updatedAt, w.rows.getValue("r1").rejected)
  }

  // A deletion the Backend refuses can never go; its mark goes rather than block every pass.
  @Test
  fun a_refused_deletion_is_dropped() = runTest {
    val w = World()
    w.deletions += PendingDeletion("ask", "a0", null)
    w.fail["delete/ask/a0"] = ApiErrorKind.BAD_REQUEST
    w.sync.pass()
    assertTrue(w.deletions.isEmpty())
  }

  @Test
  fun a_memo_removed_is_sent_as_null() = runTest {
    val w = World()
    w.memos["a1"] = World.MemoRow(null, 2_000)
    w.sync.pass()
    assertEquals(listOf("memo/a1:null"), w.calls)
  }

  // Slice 3 spec §5.1: the address and heading the Backend settled are kept with the memo.
  @Test
  fun a_point_memo_keeps_the_address_and_heading_the_backend_settled() = runTest {
    val w = World()
    w.note("n1", 2_000)
    w.saved["n1"] = NoteSaved("静岡県御殿場市新橋", 45.5)
    w.sync.pass()
    val row = w.notes.getValue("n1")
    assertEquals(Triple(2_000L, "静岡県御殿場市新橋", 45.5), Triple(row.sent, row.address, row.heading))
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  @Test
  fun a_refused_point_memo_is_not_sent_again_until_it_changes_and_a_gone_one_is_taken_as_sent() = runTest {
    val w = World()
    w.note("n1", 2_000, text = "x")
    w.note("n2", 2_100, text = "y")
    w.fail["note/r1/n1:x"] = ApiErrorKind.BAD_REQUEST
    w.fail["note/r1/n2:y"] = ApiErrorKind.GONE
    w.sync.pass()
    assertEquals(listOf("note/r1/n1:x", "refused:note/n1", "note/r1/n2:y"), w.calls)
    assertEquals(2_100L, w.notes.getValue("n2").sent)
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
    w.note("n1", 2_200, text = "z")
    w.sync.pass()
    assertEquals(listOf("note/r1/n1:z"), w.calls)
  }

  // A failed point memo ends the pass before the memos, and goes again first in the next one.
  @Test
  fun a_point_memo_that_fails_ends_the_pass() = runTest {
    val w = World()
    w.note("n1", 2_000)
    w.memos["a1"] = World.MemoRow("x", 2_000)
    w.fail["note/r1/n1:展望"] = ApiErrorKind.NETWORK
    w.sync.pass()
    assertEquals(listOf("note/r1/n1:展望"), w.calls)
    assertEquals(null, w.notes.getValue("n1").sent)
  }

  // Android #54: a mark of a kind the device cannot send is dropped, and what comes after it still goes.
  @Test
  fun a_deletion_of_an_unknown_kind_is_dropped_and_the_rest_still_goes() = runTest {
    val w = World()
    w.add(recording)
    w.deletions += PendingDeletion("photo", "p0", "r1")
    w.deletions += PendingDeletion("note", "n0", null)
    w.sync.pass()
    assertEquals(listOf("ride/r1:recording"), w.calls)
    assertTrue(w.deletions.isEmpty())
  }

  // Slice 4 spec §5.1: unreached asks go after the point memos and before the memos, so a memo finds its ask there.
  @Test
  fun an_unreached_ask_goes_before_the_memos_and_keeps_the_address_it_gets() = runTest {
    val w = World()
    w.add(recording)
    w.rows.getValue("r1").sent = recording.updatedAt
    w.note("n1", 5_000)
    w.unreached("u1", 1_400)
    w.memos["u1"] = World.MemoRow("何の山か", 6_000)
    w.sync.pass()
    assertEquals(listOf("note/r1/n1:展望", "unreached/u1:r1", "memo/u1:何の山か"), w.calls)
    assertEquals("箱根町仙石原", w.unreached.getValue("u1").address)
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  // A failure on the way ends the pass, so the memo of an unreached ask that did not go waits for it.
  @Test
  fun the_memo_of_an_unreached_ask_that_failed_to_go_waits_for_it() = runTest {
    val w = World()
    w.unreached("u1", 1_400)
    w.memos["u1"] = World.MemoRow("何の山か", 6_000)
    w.fail["unreached/u1:r1"] = ApiErrorKind.NETWORK
    w.sync.pass()
    assertEquals(listOf("unreached/u1:r1"), w.calls)
  }

  // 409: the Backend took in an ask under this id; the unreached one is refused, not sent again.
  @Test
  fun an_unreached_ask_the_backend_refuses_is_not_sent_again() = runTest {
    val w = World()
    w.unreached("u1", 1_400)
    w.fail["unreached/u1:r1"] = ApiErrorKind.CONFLICT
    w.sync.pass()
    assertEquals(listOf("unreached/u1:r1", "refused:unreached/u1"), w.calls)
    w.calls.clear()
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
  }

  // Slice 4 spec §5.2: without a network a pass does not try; what waits goes on the next trigger with one.
  @Test
  fun without_a_network_a_pass_makes_no_request() = runTest {
    val w = World()
    w.add(recording, points = 300)
    w.unreached("u1", 1_400)
    w.online = false
    w.sync.pass()
    assertEquals(emptyList<String>(), w.calls)
    w.online = true
    w.sync.pass()
    assertEquals(listOf("ride/r1:recording", "route/r1/0:300", "unreached/u1:r1"), w.calls)
  }

  // Slice 4 spec §7.1: a pass that ran to its end is SC-14's last save, even with nothing to send; one cut short is not.
  @Test
  fun only_a_pass_that_ran_to_its_end_counts_as_saved() = runTest {
    val w = World()
    w.sync.pass()
    assertEquals(1, w.saves)
    w.add(recording.end(5_000, EndReason.MANUAL, 5_000), points = 10)
    w.fail["route/r1/0:10"] = ApiErrorKind.NETWORK
    w.sync.pass()
    w.online = false
    w.sync.pass()
    w.online = true
    w.hasKey = false
    w.sync.pass()
    assertEquals(1, w.saves)
  }

  @Test
  fun sending_is_told_while_a_pass_talks_to_the_backend() = runTest {
    val w = World()
    w.gate = CompletableDeferred()
    val job = launch { w.sync.pass() }
    runCurrent()
    assertTrue(w.sync.sending.value)
    w.gate!!.complete(Unit)
    runCurrent()
    assertEquals(false, w.sync.sending.value)
    job.cancel()
  }

  // Slice 4 spec §6.3: while the cloud's records are read in, no pass runs; the triggers meanwhile make one after.
  @Test
  fun no_pass_runs_while_records_are_read_in() = runTest {
    val w = World()
    val job = launch { w.sync.run() }
    val reading = CompletableDeferred<Unit>()
    val read = launch { w.sync.withoutPasses { reading.await() } }
    runCurrent()
    w.sync.request()
    runCurrent()
    assertEquals(0, w.passes)
    reading.complete(Unit)
    runCurrent()
    assertEquals(1, w.passes)
    read.cancel()
    job.cancel()
  }
}
