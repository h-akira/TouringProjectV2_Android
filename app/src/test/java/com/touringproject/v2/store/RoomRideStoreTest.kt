package com.touringproject.v2.store

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.ride.EndReason
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RoutePoint
import com.touringproject.v2.ride.end
import com.touringproject.v2.ride.newRide
import com.touringproject.v2.ride.pause
import com.touringproject.v2.ride.resume
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRideStoreTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val dao = db.rides()
  private val store = RoomRideStore(dao)
  private val ride = newRide("r1", "箱根", 1_000, 7, 24 * HOUR_MS)

  @After
  fun close() = db.close()

  @Test
  fun a_saved_ride_comes_back_with_its_segments() = runBlocking {
    val resumed = ride.pause(5_000).resume(9_000, 7, 24 * HOUR_MS)
    store.save(ride)
    store.save(resumed)
    assertEquals(resumed, store.active())
  }

  @Test
  fun an_ended_ride_is_not_active() = runBlocking {
    store.save(ride.end(3_000, EndReason.AUTO, 3_000))
    assertNull(store.active())
    assertEquals("auto", dao.ride("r1")?.endReason)
    assertEquals("ended", dao.ride("r1")?.status)
  }

  @Test
  fun a_point_updates_the_distance_and_the_alive_time_together() = runBlocking {
    store.save(ride)
    store.addPoint("r1", RoutePoint(0, 2_000, 35.0, 139.0, 5.0), 12.5, 2_100)
    store.addPoint("r1", RoutePoint(0, 7_000, 35.001, 139.0, null), 120.0, 7_100)
    assertEquals(RoutePoint(0, 7_000, 35.001, 139.0, null), store.lastPoint("r1"))
    assertEquals(2, dao.points("r1").size)
    val active = store.active()!!
    assertEquals(120.0, active.distanceMeters, 0.0)
    assertEquals(7_100L, active.lastAliveAt)
    store.markAlive("r1", 60_000)
    assertEquals(60_000L, store.active()?.lastAliveAt)
  }

  @Test
  fun saving_again_keeps_what_the_backend_has_accepted_or_refused() = runBlocking {
    store.save(ride)
    dao.upsertRide(dao.ride("r1")!!.copy(sentUpdatedAt = 1_000, rejectedUpdatedAt = 900, routeRejected = true))
    store.save(ride.pause(5_000))
    val saved = dao.ride("r1")!!
    assertEquals(1_000L, saved.sentUpdatedAt)
    assertEquals(900L, saved.rejectedUpdatedAt)
    assertTrue(saved.routeRejected)
    assertEquals(RideStatus.PAUSED, store.active()?.status)
  }

  @Test
  fun a_ride_keeps_its_memo_and_drops_it_when_saved_without_one() = runBlocking {
    val ended = ride.end(5_000, EndReason.MANUAL, 5_000)
    store.save(ended.copy(memo = "また来たい"))
    assertEquals("また来たい", store.ride("r1")?.memo)
    store.save(ended.copy(memo = null, updatedAt = 6_000))
    assertNull(store.ride("r1")?.memo)
  }
}
