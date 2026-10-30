package com.touringproject.v2.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

interface KeyValueStore {
  suspend fun get(key: String): String?
  suspend fun set(key: String, value: String?)
}

// A corrupt file is replaced with an empty one rather than failing every launch.
private val Context.dataStore by preferencesDataStore(name = "trv2", corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

class DataStoreKeyValueStore(private val context: Context) : KeyValueStore {
  override suspend fun get(key: String): String? = context.dataStore.data.map { it[stringPreferencesKey(key)] }.first()

  override suspend fun set(key: String, value: String?) {
    context.dataStore.edit { prefs ->
      if (value == null) prefs.remove(stringPreferencesKey(key)) else prefs[stringPreferencesKey(key)] = value
    }
  }
}

const val SETTINGS_KEY = "trv2.settings"

/** Holds the settings in memory for synchronous reads by the ask loop and writes changes through to the store. */
class SettingsRepository(
  private val store: KeyValueStore,
  private val random: Random = Random.Default,
  private val logError: (String, Throwable) -> Unit = { message, e -> Log.w("trv2.settings", message, e) },
) {
  private val state = MutableStateFlow<Settings?>(null)
  val settings: StateFlow<Settings?> = state.asStateFlow()
  private val writes = Mutex()

  // True when the saved settings could not be read: the name in memory is made up for this run, and nothing is written.
  private var unreadable = false

  // The name made up for this run when the settings could not be read; it is never written nor confirmed (R2-01).
  private var madeUpName: String? = null

  /** False while the user name is made up because the settings could not be read; true once it is the rider's. */
  val nameConfirmed: Boolean get() = !unreadable

  /**
   * A store that cannot be read leaves the app on its defaults for this run and writes nothing, so the name and the
   * settings it holds are not written over (R2-01). A store with nothing in it is a first launch and is written.
   */
  suspend fun load(): Settings {
    val raw = try {
      store.get(SETTINGS_KEY).also { unreadable = false }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logError("settings store failed", e)
      unreadable = true
      null
    }
    val (loaded, needsSave) = decodeSettings(raw, random)
    madeUpName = if (unreadable) loaded.userName else null
    if (needsSave && !unreadable) guard { store.set(SETTINGS_KEY, encodeSettings(loaded)) }
    state.value = loaded
    return loaded
  }

  fun current(): Settings = checkNotNull(state.value) { "settings not loaded" }

  /**
   * Lays what changed from from to to onto the newest settings and keeps it (applyChange); nothing is written while the
   * settings could not be read.
   */
  suspend fun update(from: Settings, to: Settings) = writes.withLock {
    val next = applyChange(from, to, current())
    state.value = next
    if (!unreadable) guard { store.set(SETTINGS_KEY, encodeSettings(next)) }
  }

  /**
   * Writes next, and only then holds it; throws when the store cannot write it, leaving the settings as they were. For
   * the change of user name, which must not go on with a name that was not saved (Android #72). The rider's own choice
   * of a name makes it confirmed. The name made up when the settings could not be read (put back after a change that
   * did not go through) is only held: it is not written, and it leaves the name not confirmed (R2-01).
   */
  suspend fun save(next: Settings) = writes.withLock {
    if (madeUpName != null && next.userName == madeUpName) {
      state.value = next
      unreadable = true
      return@withLock
    }
    store.set(SETTINGS_KEY, encodeSettings(next))
    state.value = next
    unreadable = false
  }

  private suspend fun <T> guard(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    logError("settings store failed", e)
    null
  }
}
