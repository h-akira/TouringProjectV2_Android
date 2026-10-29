package com.touringproject.v2.recordings

import com.touringproject.v2.audio.PcmPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val PLAY_FAILED_MESSAGE = "再生できませんでした"
const val DELETE_FAILED_MESSAGE = "消せませんでした"

/**
 * SC-12's state and actions: the trial recording, the kept recordings and their playback. Call it from the main thread.
 * The kept recordings have their own player, so leaving SC-12 never stops an answer that is being read out.
 */
class RecordingCheck(
  private val store: RecordingStore,
  private val trial: TrialController,
  private val player: PcmPlayback,
  private val askBusy: () -> Boolean,
  private val scope: CoroutineScope,
  private val logError: (String, Throwable) -> Unit,
) {
  private val itemsState = MutableStateFlow<List<RecordingMeta>>(emptyList())
  val items: StateFlow<List<RecordingMeta>> = itemsState.asStateFlow()

  private val playingState = MutableStateFlow<String?>(null)
  /** The name of the kept recording playing now. */
  val playing: StateFlow<String?> = playingState.asStateFlow()

  private val messageState = MutableStateFlow<String?>(null)
  val message: StateFlow<String?> = messageState.asStateFlow()

  // Cancelled by a stop as well: a stop that lands while the file is still being read must keep it silent.
  private var playJob: Job? = null

  init {
    // The list follows the store: a question saved or transcribed while SC-12 is open shows up at once.
    scope.launch { store.changes.collect { reload() } }
  }

  /** The trial button: start a trial, or end the one that runs. */
  fun toggleTrial() {
    if (trial.active) {
      trial.stop()
      return
    }
    if (askBusy() || playingState.value != null) return
    messageState.value = null
    // Undispatched: the trial is RECORDING before this returns, so leaving SC-12 at once cancels it (Android #7).
    scope.launch(start = CoroutineStart.UNDISPATCHED) { trial.start() }
  }

  fun play(name: String) {
    if (askBusy() || trial.active || playingState.value != null) return
    playingState.value = name
    messageState.value = null
    playJob = scope.launch {
      val self = coroutineContext[Job]
      try {
        player.play(store.read(name))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logError("recording playback failed", e)
        messageState.value = PLAY_FAILED_MESSAGE
      } finally {
        // A stop already cleared it, and a newer playback may own it now.
        if (playJob === self) {
          playJob = null
          playingState.value = null
        }
      }
    }
  }

  /** The ■ button, and an intercom press while a kept recording plays (the microphone would pick it up). */
  fun stopPlaying() {
    if (playingState.value == null) return
    playJob?.cancel()
    playJob = null
    player.stop()
    playingState.value = null
  }

  /** After DL-01. The cloud's questions and answers stay; only the audio on this device goes. */
  fun deleteAll() {
    if (askBusy() || trial.active) return
    scope.launch {
      try {
        store.deleteAll()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logError("delete all failed", e)
        messageState.value = DELETE_FAILED_MESSAGE
      }
    }
  }

  /** Leaving SC-12: drop the trial and this screen's playback, nothing else. */
  fun leave() {
    trial.cancel()
    stopPlaying()
  }

  private suspend fun reload() {
    itemsState.value = try {
      store.list()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logError("recording list failed", e)
      emptyList()
    }
  }
}
