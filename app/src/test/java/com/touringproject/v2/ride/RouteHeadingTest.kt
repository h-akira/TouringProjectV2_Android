package com.touringproject.v2.ride

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.location.Fix
import com.touringproject.v2.location.HeadingParams
import com.touringproject.v2.location.HeadingResult
import com.touringproject.v2.location.NoHeading
import com.touringproject.v2.location.degrees
import com.touringproject.v2.store.RideDatabase
import com.touringproject.v2.store.RoomRideStore
import com.touringproject.v2.store.toPoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5 spec §4.1: the heading comes from the last segment's points in Room, with the parameters of the moment.
@RunWith(AndroidJUnit4::class)
class RouteHeadingTest {
  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), RideDatabase::class.java)
    .allowMainThreadQueries()
    .build()
  private val store = RoomRideStore(db.rides())
  private var ride: Ride? = null
  private var params = HeadingParams()
  private val failures = mutableListOf<String>()
  private val heading = RouteHeading(
    ride = { ride },
    points = { rideId, segment, count -> db.rides().segmentTail(rideId, segment, count).map { it.toPoint() } },
    params = { params },
    log = { message, _ -> failures += message },
  )

  @After
  fun close() = db.close()

  // About 11 m a step north of 35.0, 139.0.
  private fun point(segment: Int, step: Int, t: Long) = RoutePoint(segment, t, 35.0 + step * 0.0001, 139.0, 5.0)

  private fun here(step: Int, t: Long) = Fix(35.0 + step * 0.0001, 139.0, t, 5.0)

  @Test
  fun the_heading_comes_from_the_last_segment_only() = runBlocking {
    val first = newRide("r1", "箱根", 0, 7, 24 * HOUR_MS)
    store.save(first)
    // Riding south before the pause, then north after it, from far away (a ferry).
    (0..3).forEach { store.addPoint("r1", point(0, -it, it * 5_000L), 0.0, 0) }
    val resumed = first.pause(20_000).resume(600_000, 7, 24 * HOUR_MS)
    store.save(resumed)
    ride = resumed
    assertEquals(HeadingResult.None(NoHeading.NOT_MOVED), heading.find(here(-500, 600_000), 600_000))
    store.addPoint("r1", point(1, -500, 600_000), 0.0, 0)
    store.addPoint("r1", point(1, -499, 605_000), 0.0, 0)
    val north = heading.find(here(-498, 610_000), 610_000).degrees!!
    assertTrue("north: $north", north < 1.0 || north > 359.0)
  }

  @Test
  fun a_paused_ride_takes_the_segment_before_the_pause() = runBlocking {
    val started = newRide("r1", "箱根", 0, 7, 24 * HOUR_MS)
    store.save(started)
    (0..3).forEach { store.addPoint("r1", point(0, it, it * 5_000L), 0.0, 0) }
    ride = started.pause(20_000)
    assertEquals(0.0, heading.find(here(3, 15_000), 60_000).degrees!!, 1.0)
  }

  @Test
  fun the_parameters_of_the_moment_are_used() = runBlocking {
    val started = newRide("r1", "箱根", 0, 7, 24 * HOUR_MS)
    store.save(started)
    ride = started
    (0..3).forEach { store.addPoint("r1", point(0, it, it * 5_000L), 0.0, 0) }
    assertEquals(0.0, heading.find(here(3, 15_000), 15_000 + 5 * 60_000).degrees!!, 1.0)
    params = HeadingParams(holdMinutes = 2)
    assertEquals(HeadingResult.None(NoHeading.HELD_TOO_LONG), heading.find(here(3, 15_000), 15_000 + 5 * 60_000))
  }

  @Test
  fun without_a_ride_or_its_points_there_is_no_route() = runBlocking {
    assertEquals(HeadingResult.None(NoHeading.NOT_MOVED), heading.find(here(0, 0), 0))
    val broken = RouteHeading({ newRide("r1", "箱根", 0, 7, HOUR_MS) }, { _, _, _ -> error("closed") }, { params }) { message, _ -> failures += message }
    assertNull(broken.find(here(0, 0), 0).degrees)
    assertEquals(listOf("heading_points_failed rideId=r1"), failures)
  }
}
