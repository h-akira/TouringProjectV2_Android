package com.touringproject.v2.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// Android #29: saving the key or the user name lets the rides waiting on the device go without reopening the app.
class CredentialChangesTest {
  // Collected once the settings and the key are loaded: the values then are not a change, every later one is.
  @Test
  fun a_key_entered_for_the_first_time_is_a_trigger() = runTest {
    val key = MutableStateFlow<String?>(null)
    val user = MutableStateFlow<String?>("rider01")
    val triggers = mutableListOf<Unit>()
    val job = launch { credentialChanges(key, user).toList(triggers) }
    runCurrent()
    assertEquals(0, triggers.size)
    key.value = "key-1"
    runCurrent()
    assertEquals(1, triggers.size)
    job.cancel()
  }

  @Test
  fun a_change_of_the_key_or_the_user_name_is_a_trigger_but_the_loaded_values_are_not() = runTest {
    val key = MutableStateFlow<String?>("key-1")
    val user = MutableStateFlow<String?>("rider01")
    val triggers = mutableListOf<Unit>()
    val job = launch { credentialChanges(key, user).toList(triggers) }
    runCurrent()
    assertEquals(0, triggers.size)
    key.value = "key-2"
    runCurrent()
    user.value = "rider02"
    runCurrent()
    assertEquals(2, triggers.size)
    // Removing the key sends nothing; putting it back, even the same one, is a change (Android #29).
    key.value = null
    runCurrent()
    assertEquals(2, triggers.size)
    key.value = "key-2"
    runCurrent()
    assertEquals(3, triggers.size)
    job.cancel()
  }
}
