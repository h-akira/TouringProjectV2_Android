package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOwnerTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val owner = RoomOwner(db.takeover())

  @After
  fun close() = db.close()

  @Test
  fun a_new_device_has_no_owner() = runBlocking {
    assertNull(owner.owner())
  }

  @Test
  fun the_owner_is_kept_and_replaced() = runBlocking {
    owner.adopt("rider01")
    assertEquals("rider01", owner.owner())
    owner.adopt("taro01")
    assertEquals("taro01", owner.owner())
  }
}
