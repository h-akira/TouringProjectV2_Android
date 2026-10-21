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
  @Test
  fun a_change_of_the_key_or_the_user_name_is_a_trigger_but_loading_them_is_not() = runTest {
    val key = MutableStateFlow<String?>(null)
    val user = MutableStateFlow<String?>(null)
    val triggers = mutableListOf<Unit>()
    val job = launch { credentialChanges(key, user).toList(triggers) }
    runCurrent()
    user.value = "rider01"
    runCurrent()
    key.value = "key-1"
    runCurrent()
    assertEquals(0, triggers.size)
    key.value = "key-2"
    runCurrent()
    user.value = "rider02"
    runCurrent()
    assertEquals(2, triggers.size)
    // Removing the key sends nothing; putting it back is a change.
    key.value = null
    runCurrent()
    assertEquals(2, triggers.size)
    key.value = "key-2"
    runCurrent()
    assertEquals(2, triggers.size)
    key.value = "key-3"
    runCurrent()
    assertEquals(3, triggers.size)
    job.cancel()
  }
}
