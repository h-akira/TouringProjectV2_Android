package com.touringproject.v2.settings

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val LAST_SAVED_KEY = "trv2.lastSavedAt"

/**
 * When a pass of the sync last ran to its end (SC-14's 最終保存, slice 4 spec §7.1), kept beside the settings in the
 * device's time. A store that cannot be read or written leaves it unknown, as a fresh install has it.
 */
class LastSaved(
  private val store: KeyValueStore,
  private val logError: (String, Throwable) -> Unit = { message, e -> Log.w("trv2.settings", message, e) },
) {
  private val state = MutableStateFlow<Long?>(null)
  val at: StateFlow<Long?> = state.asStateFlow()

  suspend fun load() {
    state.value = guard { store.get(LAST_SAVED_KEY) }?.toLongOrNull()
  }

  suspend fun mark(at: Long) {
    state.value = at
    guard { store.set(LAST_SAVED_KEY, at.toString()) }
  }

  private suspend fun <T> guard(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    logError("last saved store failed", e)
    null
  }
}
