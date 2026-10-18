package com.touringproject.v2.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
      .addMigrations(MIGRATION_1_2)
      .allowMainThreadQueries()
      .build()
    runBlocking {
      val ride = db.rides().ride("r1")!!
      assertEquals(6_000L, ride.sentUpdatedAt)
      assertNull(ride.rejectedUpdatedAt)
      assertFalse(ride.routeRejected)
      assertEquals(1, db.rides().points("r1").size)
      assertNull(db.asks().ask("a1"))
    }
    db.close()
  }
}
