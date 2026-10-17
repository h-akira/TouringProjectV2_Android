package com.touringproject.v2.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.random.Random

interface KeyValueStore {
  suspend fun get(key: String): String?
  suspend fun set(key: String, value: String?)
}

private val Context.dataStore by preferencesDataStore(name = "trv2")

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
class SettingsRepository(private val store: KeyValueStore, private val random: Random = Random.Default) {
  private val state = MutableStateFlow<Settings?>(null)
  val settings: StateFlow<Settings?> = state.asStateFlow()

  suspend fun load(): Settings {
    val (loaded, needsSave) = decodeSettings(store.get(SETTINGS_KEY), random)
    if (needsSave) store.set(SETTINGS_KEY, encodeSettings(loaded))
    state.value = loaded
    return loaded
  }

  fun current(): Settings = checkNotNull(state.value) { "settings not loaded" }

  suspend fun update(next: Settings) {
    state.value = next
    store.set(SETTINGS_KEY, encodeSettings(next))
  }
}
