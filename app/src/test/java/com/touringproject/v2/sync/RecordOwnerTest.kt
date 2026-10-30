package com.touringproject.v2.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOwner(var owner: String? = null) : OwnerStore {
  val adopted = mutableListOf<String>()
  var failRead = false
  override suspend fun owner(): String? {
    if (failRead) throw java.io.IOException("room")
    return owner
  }
  override suspend fun adopt(userName: String) {
    adopted += userName
    owner = userName
  }
}

class RecordOwnerTest {
  private fun owner(store: OwnerStore, name: String? = "rider01", confirmed: Boolean = true) =
    RecordOwner(store, { name }, { confirmed }) { _, _ -> }

  @Test
  fun the_name_of_the_owner_may_send() = runTest {
    val owner = owner(FakeOwner("rider01"))
    assertTrue(owner.check())
    assertEquals(OwnerState.MATCH, owner.state.value)
  }

  // Review Focus 1: a device from before the owner was kept takes the name of the settings, and goes on sending.
  @Test
  fun a_device_without_an_owner_adopts_the_name_of_the_settings() = runTest {
    val store = FakeOwner(null)
    val owner = owner(store)
    assertTrue(owner.check())
    assertEquals(listOf("rider01"), store.adopted)
  }

  // R2-02: a name that is not the owner's sends nothing.
  @Test
  fun another_name_than_the_owners_sends_nothing() = runTest {
    val owner = owner(FakeOwner("taro01"))
    assertFalse(owner.check())
    assertEquals(OwnerState.MISMATCH, owner.state.value)
  }

  // R2-01, Review Focus 2: a name made up because the settings could not be read is neither adopted nor sent under.
  @Test
  fun an_unconfirmed_name_sends_nothing() = runTest {
    val store = FakeOwner(null)
    val owner = owner(store, confirmed = false)
    assertFalse(owner.check())
    assertEquals(OwnerState.UNCONFIRMED, owner.state.value)
    assertEquals(emptyList<String>(), store.adopted)
  }

  @Test
  fun the_settings_not_yet_loaded_send_nothing() = runTest {
    val owner = owner(FakeOwner("rider01"), name = null)
    assertFalse(owner.check())
    assertEquals(OwnerState.UNKNOWN, owner.state.value)
  }

  @Test
  fun an_owner_that_cannot_be_read_sends_nothing() = runTest {
    val store = FakeOwner("rider01").apply { failRead = true }
    val owner = owner(store)
    assertFalse(owner.check())
    assertEquals(OwnerState.UNKNOWN, owner.state.value)
  }

  @Test
  fun the_state_follows_the_name() = runTest {
    var name: String? = "taro01"
    val owner = RecordOwner(FakeOwner("rider01"), { name }, { true }) { _, _ -> }
    assertFalse(owner.check())
    name = "rider01"
    assertTrue(owner.check())
    assertEquals(OwnerState.MATCH, owner.state.value)
  }
}
