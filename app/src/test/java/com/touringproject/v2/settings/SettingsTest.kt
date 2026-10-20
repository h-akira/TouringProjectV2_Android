package com.touringproject.v2.settings

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
    val s = Settings("rider01", returnEnabled = false, returnApp = maps, returnTiming = ReturnTiming.SPOKEN, recentApps = listOf(maps), answerVolumeDb = 3, maxRecordingHours = 6, showAskPins = false)
    repo.update(s)
    assertEquals(s, SettingsRepository(store).load())
  }

  @Test
  fun broken_values_fall_back_to_defaults() {
    val raw = """{"userName":"NO","returnEnabled":"yes","returnApp":{"packageName":1},"returnTiming":"later","recentApps":[{"packageName":"a","label":"A"},3],"answerVolumeDb":12,"maxRecordingHours":72,"showAskPins":"no"}"""
    val (s, needsSave) = decodeSettings(raw, Random(2))
    assertTrue(needsSave)
    assertTrue(s.returnEnabled)
    assertNull(s.returnApp)
    assertEquals(ReturnTiming.SENT, s.returnTiming)
    assertEquals(listOf(LaunchableApp("a", "A")), s.recentApps)
    assertEquals(DEFAULT_VOLUME_DB, s.answerVolumeDb)
    assertEquals(DEFAULT_MAX_RECORDING_HOURS, s.maxRecordingHours)
    assertTrue(s.showAskPins)
  }

  @Test
  fun the_longest_recording_time_is_24_hours_until_chosen_from_6_12_24_48() {
    assertEquals(24, decodeSettings("""{"userName":"rider01"}""").first.maxRecordingHours)
    assertEquals(listOf(6, 12, 24, 48), MAX_RECORDING_HOURS_CHOICES)
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
