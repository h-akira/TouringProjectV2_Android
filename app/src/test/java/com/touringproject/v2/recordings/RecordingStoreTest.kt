package com.touringproject.v2.recordings

import com.touringproject.v2.ask.RecordingMeta
import com.touringproject.v2.audio.InputDevice
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun meta(time: Long, transcript: String? = null) =
  RecordingMeta("rec-$time.pcm", time, 1_500, false, InputDevice(InputDevice.Type.BLUETOOTH, "M1-S PRO"), transcript)

class RecordingStoreTest {
  @get:Rule val folder = TemporaryFolder()
  private val dir get() = File(folder.root, "recordings")

  @Test
  fun a_saved_recording_is_listed_and_can_be_read_back() = runTest {
    val store = RecordingStore(dir)
    store.save(meta(100), byteArrayOf(1, 2, 3))
    assertEquals(listOf(meta(100)), store.list())
    assertArrayEquals(byteArrayOf(1, 2, 3), store.read("rec-100.pcm"))
  }

  @Test
  fun the_eleventh_recording_removes_the_oldest_with_its_json() = runTest {
    val store = RecordingStore(dir)
    (1L..11L).forEach { store.save(meta(it), byteArrayOf(it.toByte())) }
    assertEquals((11L downTo 2L).toList(), store.list().map { it.recordedAt })
    assertFalse(File(dir, "rec-1.pcm").exists())
    assertFalse(File(dir, "rec-1.json").exists())
    assertEquals(20, dir.list()!!.size)
  }

  @Test
  fun a_recording_missing_half_of_its_files_does_not_take_a_slot() = runTest {
    dir.mkdirs()
    File(dir, "rec-1.pcm").writeBytes(byteArrayOf(1)) // no JSON
    File(dir, "rec-2.pcm").writeBytes(byteArrayOf(1))
    File(dir, "rec-2.json").writeText("{not json")
    File(dir, "rec-3.json").writeText("""{"name":"rec-3.pcm","recordedAt":3,"durationMs":1,"phoneMic":false,"device":null,"transcript":null}""") // no PCM
    val store = RecordingStore(dir)
    (100L..109L).forEach { store.save(meta(it), byteArrayOf(1)) }
    assertEquals(10, store.list().size)
    assertEquals(20, dir.list()!!.size)
  }

  @Test
  fun what_was_heard_is_added_later() = runTest {
    val store = RecordingStore(dir)
    store.save(meta(100), byteArrayOf(1))
    store.setTranscript("rec-100.pcm", "富士山の高さは")
    assertEquals(listOf(meta(100, "富士山の高さは")), store.list())
  }

  @Test
  fun a_transcript_for_a_recording_that_is_gone_creates_nothing() = runTest {
    val store = RecordingStore(dir)
    store.setTranscript("rec-100.pcm", "x")
    store.setTranscript("../evil.pcm", "x")
    assertFalse(dir.exists())
  }

  @Test
  fun delete_all_then_a_new_recording_is_still_kept() = runTest {
    // An ask that is recording while "delete all" runs must still keep its recording (React Native version's issue #11).
    val store = RecordingStore(dir)
    store.save(meta(100), byteArrayOf(1))
    store.deleteAll()
    assertEquals(emptyList<RecordingMeta>(), store.list())
    store.save(meta(200), byteArrayOf(2))
    assertEquals(listOf(meta(200)), store.list())
  }

  @Test
  fun every_change_is_announced() = runTest {
    val store = RecordingStore(dir)
    val before = store.changes.value
    store.save(meta(100), byteArrayOf(1))
    store.setTranscript("rec-100.pcm", "x")
    store.deleteAll()
    assertEquals(before + 3, store.changes.value)
  }

  @Test
  fun the_keeper_never_lets_a_store_failure_reach_the_ask() = runTest {
    // A file where the directory should be: every write fails.
    folder.root.resolve("recordings").writeText("not a directory")
    val errors = mutableListOf<String>()
    // The store's file work runs on the test scheduler here, so advanceUntilIdle waits for it.
    val keeper = RecordingKeeper(RecordingStore(dir, io = StandardTestDispatcher(testScheduler)), this) { message, _ -> errors += message }
    keeper.saved(meta(100), byteArrayOf(1))
    keeper.transcribed("rec-100.pcm", "x")
    testScheduler.advanceUntilIdle()
    assertTrue(errors.contains("recording save failed"))
  }
}
