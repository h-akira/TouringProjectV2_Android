package com.touringproject.v2.recordings

import com.touringproject.v2.ask.RecordingMeta
import com.touringproject.v2.ask.RecordingSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

// The newest recordings stay on the device for SC-12 (slice 1 spec §5.4): rec-<time>.pcm with rec-<time>.json beside it.
const val KEEP_RECORDINGS = 10
private val FILE_RE = Regex("^rec-(\\d+)\\.(pcm|json)$")
private val PCM_RE = Regex("^rec-\\d+\\.pcm$")

fun metaName(pcmName: String): String = pcmName.removeSuffix(".pcm") + ".json"

/** Files to delete so that only the newest [keep] complete recordings remain. A PCM without its JSON (or the reverse) goes too. */
fun recordingsToDelete(names: Collection<String>, keep: Int = KEEP_RECORDINGS): List<String> {
  val byTime = names.mapNotNull { name -> FILE_RE.matchEntire(name)?.let { it.groupValues[1].toLong() to name } }
    .groupBy({ it.first }, { it.second })
  val kept = byTime.filterValues { it.size == 2 }.keys.sortedDescending().take(keep).toSet()
  return byTime.filterKeys { it !in kept }.values.flatten().sorted()
}

/** The kept recordings on disk. Every change runs under one lock, so a save, a prune and "delete all" never interleave. */
class RecordingStore(private val dir: File, private val io: CoroutineDispatcher = Dispatchers.IO, private val keep: Int = KEEP_RECORDINGS) {
  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private val changeCount = MutableStateFlow(0)

  /** Bumped after every change, so SC-12 can reload its list. */
  val changes: StateFlow<Int> = changeCount.asStateFlow()

  suspend fun save(meta: RecordingMeta, pcm: ByteArray) = change {
    require(PCM_RE.matches(meta.name)) { "bad recording name" }
    dir.mkdirs()
    File(dir, meta.name).writeBytes(pcm)
    File(dir, metaName(meta.name)).writeText(json.encodeToString(RecordingMeta.serializer(), meta))
    // A JSON that cannot be read would hold a slot without ever being listed.
    dir.listFiles { file -> file.name.endsWith(".json") }?.filter { parse(it) == null }?.forEach { it.delete() }
    recordingsToDelete(dir.list()?.toList() ?: emptyList(), keep).forEach { File(dir, it).delete() }
  }

  /** Adds what the Backend heard; does nothing when the recording is already gone. */
  suspend fun setTranscript(name: String, transcript: String) = change {
    if (!PCM_RE.matches(name)) return@change
    val file = File(dir, metaName(name))
    val meta = parse(file) ?: return@change
    file.writeText(json.encodeToString(RecordingMeta.serializer(), meta.copy(transcript = transcript)))
  }

  /** Newest first; only recordings whose PCM and JSON are both there. */
  suspend fun list(): List<RecordingMeta> = mutex.withLock {
    withContext(io) {
      val names = dir.list()?.toSet() ?: emptySet()
      names.filter { it.endsWith(".json") }
        .mapNotNull { name -> parse(File(dir, name))?.takeIf { metaName(it.name) == name && it.name in names } }
        .sortedByDescending { it.recordedAt }
    }
  }

  suspend fun read(name: String): ByteArray = mutex.withLock {
    require(PCM_RE.matches(name)) { "bad recording name" }
    withContext(io) { File(dir, name).readBytes() }
  }

  /** Removes one recording with its JSON; nothing happens when it is already gone. */
  suspend fun delete(name: String) = change {
    if (!PCM_RE.matches(name)) return@change
    File(dir, name).delete()
    File(dir, metaName(name)).delete()
  }

  suspend fun deleteAll() = change { dir.deleteRecursively() }

  private suspend fun change(block: () -> Unit) {
    try {
      mutex.withLock { withContext(io) { block() } }
    } finally {
      changeCount.update { it + 1 }
    }
  }

  private fun parse(file: File): RecordingMeta? =
    runCatching { json.decodeFromString(RecordingMeta.serializer(), file.readText()) }.getOrNull()?.takeIf { PCM_RE.matches(it.name) }
}

/** What the ask loop records goes to the store in the background; a failure there is only logged, never fails the ask. */
class RecordingKeeper(
  private val store: RecordingStore,
  private val scope: CoroutineScope,
  private val logError: (String, Throwable) -> Unit,
) : RecordingSink {
  override fun saved(meta: RecordingMeta, pcm: ByteArray) {
    scope.launch { guard("save") { store.save(meta, pcm) } }
  }

  override fun transcribed(name: String, transcript: String) {
    scope.launch { guard("transcript") { store.setTranscript(name, transcript) } }
  }

  // Launched after the save on the same scope; the store's lock takes them in that order.
  override fun discarded(name: String) {
    scope.launch { guard("discard") { store.delete(name) } }
  }

  private suspend fun guard(what: String, block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logError("recording $what failed", e)
    }
  }
}
