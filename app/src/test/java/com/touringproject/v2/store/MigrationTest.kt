package com.touringproject.v2.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val DB = "migration-test.db"

@RunWith(AndroidJUnit4::class)
class MigrationTest {
  @get:Rule
  val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RideDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

  @Test
  fun version_1_moves_to_2_keeping_rides_and_points() {
    helper.createDatabase(DB, 1).apply {
      execSQL("INSERT INTO rides VALUES ('r1', '箱根', 'ended', 'manual', NULL, 5000, 120.0, 6000, 6000)")
      execSQL("INSERT INTO ride_segments VALUES ('r1', 0, 1000, 5000, 7)")
      execSQL("INSERT INTO route_points (rideId, segment, t, lat, lon, accuracy, chunk) VALUES ('r1', 0, 2000, 35.0, 139.0, 5.0, NULL)")
      close()
    }
    helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2).close()
    val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RideDatabase::class.java, DB)
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
      .allowMainThreadQueries()
      .build()
    runBlocking {
      val ride = db.rides().ride("r1")!!
      assertEquals(6_000L, ride.sentUpdatedAt)
      assertNull(ride.rejectedUpdatedAt)
      assertEquals(1, db.rides().points("r1").size)
      assertNull(db.asks().ask("a1"))
    }
    db.close()
  }

  // Android #27: which chunk a version 2 ride had refused is not known, so its sent points all count as refused.
  @Test
  fun version_2_moves_to_3_marking_the_sent_points_of_a_ride_whose_route_was_refused() {
    helper.createDatabase(DB, 2).apply {
      execSQL("INSERT INTO rides VALUES ('r1', '箱根', 'ended', 'manual', NULL, 5000, 120.0, 6000, 6000, NULL, 1)")
      execSQL("INSERT INTO rides VALUES ('r2', '御殿場', 'ended', 'manual', NULL, 5000, 80.0, 6000, 6000, NULL, 0)")
      execSQL("INSERT INTO route_points (rideId, segment, t, lat, lon, accuracy, chunk) VALUES ('r1', 0, 2000, 35.0, 139.0, 5.0, 0)")
      execSQL("INSERT INTO route_points (rideId, segment, t, lat, lon, accuracy, chunk) VALUES ('r1', 0, 3000, 35.0, 139.0, 5.0, NULL)")
      execSQL("INSERT INTO route_points (rideId, segment, t, lat, lon, accuracy, chunk) VALUES ('r2', 0, 2000, 35.0, 139.0, 5.0, 0)")
      close()
    }
    helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).close()
    val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RideDatabase::class.java, DB)
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
      .allowMainThreadQueries()
      .build()
    runBlocking {
      assertEquals(listOf(true, false), db.rides().points("r1").map { it.refused })
      assertEquals(listOf(false), db.rides().points("r2").map { it.refused })
    }
    db.close()
  }
}
