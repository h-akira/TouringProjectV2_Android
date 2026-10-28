package com.touringproject.v2.settings

import com.touringproject.v2.location.HeadingParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FakeStore : KeyValueStore {
  val values = mutableMapOf<String, String>()
  override suspend fun get(key: String) = values[key]
  override suspend fun set(key: String, value: String?) {
    if (value == null) values.remove(key) else values[key] = value
  }
}

class SettingsTest {
  private val maps = LaunchableApp("com.google.android.apps.maps", "マップ")

  @Test
  fun random_user_name_is_6_lowercase_letters_or_digits() {
    repeat(50) { assertTrue(Regex("^[a-z0-9]{6}$").matches(randomUserName(Random(it))) ) }
  }

  @Test
  fun first_load_creates_and_saves_a_user_name_with_defaults() = runTest {
    val store = FakeStore()
    val loaded = SettingsRepository(store, Random(1)).load()
    assertTrue(USER_NAME_RE.matches(loaded.userName))
    assertEquals(Settings(userName = loaded.userName), loaded)
    assertEquals(loaded, decodeSettings(store.values[SETTINGS_KEY]).first)
  }

  @Test
  fun saved_settings_round_trip() = runTest {
    val store = FakeStore()
    val repo = SettingsRepository(store)
    val s = Settings("rider01", returnEnabled = false, returnApp = maps, returnTiming = ReturnTiming.SPOKEN, recentApps = listOf(maps), answerVolumeDb = 3, maxRecordingHours = 6, showAskPins = false, showNotePins = false)
    repo.update(s)
    assertEquals(s, SettingsRepository(store).load())
  }

  @Test
  fun broken_values_fall_back_to_defaults() {
    val raw = """{"userName":"NO","returnEnabled":"yes","returnApp":{"packageName":1},"returnTiming":"later","recentApps":[{"packageName":"a","label":"A"},3],"answerVolumeDb":12,"maxRecordingHours":72,"showAskPins":"no","showNotePins":0}"""
    val (s, needsSave) = decodeSettings(raw, Random(2))
    assertTrue(needsSave)
    assertTrue(s.returnEnabled)
    assertNull(s.returnApp)
    assertEquals(ReturnTiming.SENT, s.returnTiming)
    assertEquals(listOf(LaunchableApp("a", "A")), s.recentApps)
    assertEquals(DEFAULT_VOLUME_DB, s.answerVolumeDb)
    assertEquals(DEFAULT_MAX_RECORDING_HOURS, s.maxRecordingHours)
    assertTrue(s.showAskPins)
    assertTrue(s.showNotePins)
  }

  // Settings saved before slice 3 have no choice for the point memos' pins: they are shown.
  @Test
  fun the_point_memos_pins_are_shown_until_hidden() {
    assertTrue(decodeSettings("""{"userName":"rider01","showAskPins":false}""").first.showNotePins)
  }

  @Test
  fun the_longest_recording_time_is_24_hours_until_chosen_from_6_12_24_48() {
    assertEquals(24, decodeSettings("""{"userName":"rider01"}""").first.maxRecordingHours)
    assertEquals(listOf(6, 12, 24, 48), MAX_RECORDING_HOURS_CHOICES)
  }

  // Slice 5 spec §6 and §4.3: SC-11's and SC-22's values, with their defaults until chosen.
  @Test
  fun recording_and_heading_values_start_at_their_defaults() {
    val s = decodeSettings("""{"userName":"rider01"}""").first
    assertEquals(20, s.recordLimitSeconds)
    assertEquals(20_000L, s.recordLimitMs)
    assertEquals(1_500, s.startCueMs)
    assertEquals(HeadingParams(referenceMeters = 15, accuracyMeters = 20, holdMinutes = 10), s.headingParams)
    assertEquals(listOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60), RECORD_LIMIT_SECONDS_CHOICES)
    assertEquals(listOf(0, 500, 1_000, 1_500, 2_000, 3_000), START_CUE_MS_CHOICES)
    assertEquals(listOf(10, 15, 20, 30), HEADING_REFERENCE_M_CHOICES)
    assertEquals(listOf(10, 20, 30, 50), HEADING_ACCURACY_M_CHOICES)
    assertEquals(listOf(2, 5, 10, 30), HEADING_HOLD_MINUTES_CHOICES)
  }

  @Test
  fun recording_and_heading_values_round_trip_and_odd_ones_fall_back() = runTest {
    val store = FakeStore()
    val s = Settings("rider01", recordLimitSeconds = 45, startCueMs = 0, headingReferenceMeters = 30, headingAccuracyMeters = 50, headingHoldMinutes = 2)
    SettingsRepository(store).update(s)
    assertEquals(s, SettingsRepository(store).load())
    val odd = """{"userName":"rider01","recordLimitSeconds":12,"startCueMs":"long","headingReferenceMeters":5,"headingAccuracyMeters":100,"headingHoldMinutes":60}"""
    assertEquals(Settings("rider01"), decodeSettings(odd).first)
  }

  // SC-11's 「既定値に戻す」 puts back its two values only.
  @Test
  fun the_recording_defaults_leave_the_other_settings_as_they_are() {
    val s = Settings("rider01", answerVolumeDb = 3, recordLimitSeconds = 45, startCueMs = 0, headingHoldMinutes = 2)
    assertEquals(s.copy(recordLimitSeconds = 20, startCueMs = 1_500), s.withRecordingDefaults())
  }

  // SC-22's 「既定値に戻す」 puts back its three values only.
  @Test
  fun the_heading_defaults_leave_the_other_settings_as_they_are() {
    val s = Settings("rider01", startCueMs = 0, headingReferenceMeters = 30, headingAccuracyMeters = 50, headingHoldMinutes = 2)
    assertEquals(s.copy(headingReferenceMeters = 15, headingAccuracyMeters = 20, headingHoldMinutes = 10), s.withHeadingDefaults())
  }

  @Test
  fun not_json_falls_back_to_defaults() {
    val (s, needsSave) = decodeSettings("{not json", Random(3))
    assertTrue(needsSave)
    assertEquals(Settings(userName = s.userName), s)
    assertFalse(decodeSettings(encodeSettings(s)).second)
  }

  @Test
  fun a_store_that_cannot_be_read_falls_back_to_defaults() = runTest {
    val broken = object : KeyValueStore {
      override suspend fun get(key: String): String? = throw java.io.IOException("corrupt")
      override suspend fun set(key: String, value: String?) = throw java.io.IOException("read-only")
    }
    val errors = mutableListOf<String>()
    val repo = SettingsRepository(broken, Random(4)) { message, _ -> errors += message }
    val loaded = repo.load()
    assertEquals(Settings(userName = loaded.userName), loaded)
    repo.update(loaded.copy(returnEnabled = false))
    assertFalse(repo.current().returnEnabled)
    assertEquals(3, errors.size)
  }

  // Android #72: the change of user name must know its save failed; the settings stay as they were.
  @Test
  fun a_save_that_cannot_be_written_throws_and_keeps_the_settings() = runTest {
    val store = FakeStore()
    val repo = SettingsRepository(store, Random(5))
    val loaded = repo.load()
    repo.save(loaded.copy(userName = "taro01"))
    assertEquals("taro01", SettingsRepository(store).load().userName)
    val readOnly = object : KeyValueStore {
      override suspend fun get(key: String): String? = store.get(key)
      override suspend fun set(key: String, value: String?) = throw java.io.IOException("disk full")
    }
    val stuck = SettingsRepository(readOnly).apply { load() }
    assertTrue(runCatching { stuck.save(stuck.current().copy(userName = "hanako02")) }.isFailure)
    assertEquals("taro01", stuck.current().userName)
  }

  @Test
  fun recent_apps_keep_the_latest_five_without_duplicates() {
    val apps = (1..6).map { LaunchableApp("p$it", "A$it") }
    var recent = emptyList<LaunchableApp>()
    apps.forEach { recent = withRecentApp(recent, it) }
    assertEquals(listOf("p6", "p5", "p4", "p3", "p2"), recent.map { it.packageName })
    assertEquals(listOf("p4", "p6", "p5", "p3", "p2"), withRecentApp(recent, apps[3]).map { it.packageName })
  }

  @Test
  fun mask_shows_only_the_last_four_characters() {
    assertEquals("••••••••7890", maskKey("abcdef1234567890"))
    assertEquals("••••", maskKey("abcd"))
  }

  // Android #33: AppGraph moves the recording ride's deadline on each change, not on the first load.
  @Test
  fun only_a_changed_longest_recording_time_is_passed_on() = runTest {
    val settings = MutableStateFlow<Settings?>(null)
    val seen = mutableListOf<Int>()
    val job = backgroundScope.launch { maxRecordingChanges(settings).collect { seen += it } }
    testScheduler.runCurrent()
    settings.value = Settings("rider01")
    testScheduler.runCurrent()
    settings.value = Settings("rider01", answerVolumeDb = 3)
    testScheduler.runCurrent()
    settings.value = Settings("rider01", maxRecordingHours = 6)
    testScheduler.runCurrent()
    settings.value = Settings("rider01", maxRecordingHours = 48)
    testScheduler.runCurrent()
    assertEquals(listOf(6, 48), seen)
    job.cancel()
  }
}

class ApiKeyStoreTest {
  // Reverses the bytes: enough to tell sealed from plain without the Android Keystore.
  private class FakeCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.reversedArray()
    override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
  }

  @Test
  fun the_key_is_stored_sealed_and_read_back() = runTest {
    val store = FakeStore()
    ApiKeyStore(store, FakeCipher()).save("secret-key")
    assertFalse(store.values[API_KEY_KEY]!!.contains("secret-key"))
    val reloaded = ApiKeyStore(store, FakeCipher())
    assertEquals("secret-key", reloaded.load())
    assertEquals("secret-key", reloaded.current())
  }

  @Test
  fun deleting_the_key_removes_it() = runTest {
    val store = FakeStore()
    val keys = ApiKeyStore(store, FakeCipher())
    keys.save("secret-key")
    keys.save(null)
    assertNull(store.values[API_KEY_KEY])
    assertNull(ApiKeyStore(store, FakeCipher()).load())
  }

  @Test
  fun a_key_that_cannot_be_decrypted_reads_as_no_key() = runTest {
    val store = FakeStore()
    store.values[API_KEY_KEY] = "AAAA"
    val broken = object : SecretCipher {
      override fun encrypt(plain: ByteArray) = plain
      override fun decrypt(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
    }
    assertNull(ApiKeyStore(store, broken).load())
  }

  @Test
  fun a_key_that_cannot_be_sealed_leaves_the_old_one_in_use() = runTest {
    val store = FakeStore()
    var failing = false
    val cipher = object : SecretCipher {
      override fun encrypt(plain: ByteArray): ByteArray = if (failing) throw java.security.KeyStoreException("no key") else plain.reversedArray()
      override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
    val keys = ApiKeyStore(store, cipher)
    keys.save("old-key")
    failing = true
    assertTrue(runCatching { keys.save("new-key") }.isFailure)
    assertEquals("old-key", keys.current())
    assertEquals("old-key", ApiKeyStore(store, cipher).load())
  }
}

class LastSavedTest {
  @Test
  fun the_last_save_is_kept_beside_the_settings() = runTest {
    val store = FakeStore()
    LastSaved(store).mark(1_700_000_000_000)
    val again = LastSaved(store)
    assertEquals(null, again.at.value)
    again.load()
    assertEquals(1_700_000_000_000, again.at.value)
  }

  @Test
  fun a_store_that_cannot_be_read_leaves_it_unknown() = runTest {
    val broken = object : KeyValueStore {
      override suspend fun get(key: String): String? = throw java.io.IOException("corrupt")
      override suspend fun set(key: String, value: String?) = throw java.io.IOException("read-only")
    }
    val saved = LastSaved(broken) { _, _ -> }
    saved.load()
    assertEquals(null, saved.at.value)
    saved.mark(5)
    assertEquals(5L, saved.at.value)
  }
}
