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
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
      .allowMainThreadQueries()
      .build()
    runBlocking {
      assertEquals(listOf(true, false), db.rides().points("r1").map { it.refused })
      assertEquals(listOf(false), db.rides().points("r2").map { it.refused })
    }
    db.close()
  }

  // Slice 2 spec §4.7 (adr/0008): asks are taken only while a ride records, and the ones kept without a ride go.
  @Test
  fun version_3_moves_to_4_dropping_the_asks_kept_without_a_ride() {
    helper.createDatabase(DB, 3).apply {
      execSQL("INSERT INTO rides VALUES ('r1', '箱根', 'ended', 'manual', NULL, 5000, 120.0, 6000, 6000, NULL, 0)")
      val columns = "(askId, askedAt, answeredAt, transcript, answer, latitude, longitude, address, headingDegrees, input, conversationId, newConversation, status, failure, rideId)"
      execSQL("INSERT INTO asks $columns VALUES ('a1', 1000, 2000, 'Q', 'A', 35.0, 139.0, '箱根町', 90.0, 'voice', 'c1', 1, 'answered', NULL, 'r1')")
      execSQL("INSERT INTO asks $columns VALUES ('a2', 3000, 4000, 'Q', 'A', 35.0, 139.0, '箱根町', 90.0, 'voice', 'c1', 0, 'answered', NULL, NULL)")
      close()
    }
    helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4).close()
    val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RideDatabase::class.java, DB)
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
      .allowMainThreadQueries()
      .build()
    runBlocking {
      assertEquals("r1", db.asks().ask("a1")?.rideId)
      assertNull(db.asks().ask("a2"))
    }
    db.close()
  }

  // Slice 3 spec §4.1: memos, point memos and deletions are added; what was kept stays as it was.
  @Test
  fun version_4_moves_to_5_keeping_rides_and_asks_without_memos() {
    helper.createDatabase(DB, 4).apply {
      execSQL("INSERT INTO rides VALUES ('r1', '箱根', 'ended', 'manual', NULL, 5000, 120.0, 6000, 6000, NULL, 0)")
      execSQL("INSERT INTO ride_segments VALUES ('r1', 0, 1000, 5000, 7)")
      val columns = "(askId, askedAt, answeredAt, transcript, answer, latitude, longitude, address, headingDegrees, input, conversationId, newConversation, status, failure, rideId)"
      execSQL("INSERT INTO asks $columns VALUES ('a1', 1000, 2000, 'Q', 'A', 35.0, 139.0, '箱根町', 90.0, 'voice', 'c1', 1, 'answered', NULL, 'r1')")
      close()
    }
    helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5).close()
    val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RideDatabase::class.java, DB)
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
      .allowMainThreadQueries()
      .build()
    runBlocking {
      val ride = db.rides().ride("r1")!!
      assertEquals("箱根", ride.name)
      assertNull(ride.memo)
      val ask = db.asks().ask("a1")!!
      assertEquals("Q", ask.transcript)
      assertNull(ask.memo)
      assertNull(ask.memoUpdatedAt)
    }
    db.close()
  }

  // Slice 4 spec §4.2: the asks table is made again so that an unreached ask can have no conversation; what was kept
  // stays as it was, memos and their markers included.
  @Test
  fun version_5_moves_to_6_keeping_asks_and_letting_an_ask_have_no_conversation() {
    helper.createDatabase(DB, 5).apply {
      execSQL("INSERT INTO rides VALUES ('r1', '箱根', 'ended', 'manual', NULL, 5000, 120.0, 6000, 6000, NULL, 0, NULL)")
      val columns = "(askId, askedAt, answeredAt, transcript, answer, latitude, longitude, address, headingDegrees, input, conversationId, " +
        "newConversation, status, failure, rideId, memo, memoUpdatedAt, memoSentUpdatedAt, memoRejectedUpdatedAt)"
      execSQL("INSERT INTO asks $columns VALUES ('a1', 1000, 2000, 'Q', 'A', 35.0, 139.0, '箱根町', 90.0, 'voice', 'c1', 1, 'answered', NULL, 'r1', '晴れ', 3000, 3000, NULL)")
      close()
    }
    helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6).close()
    val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RideDatabase::class.java, DB)
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
      .allowMainThreadQueries()
      .build()
    runBlocking {
      val ask = db.asks().ask("a1")!!
      assertEquals("c1", ask.conversationId)
      assertEquals("晴れ", ask.memo)
      assertEquals(3000L, ask.memoSentUpdatedAt)
      assertNull(ask.updatedAt)
      db.asks().upsert(ask.copy(askId = "a2", conversationId = null, status = "offline", updatedAt = 4000))
      assertNull(db.asks().ask("a2")!!.conversationId)
    }
    db.close()
  }

  // Slice 6 plan 5 (adr/0015): the owner of the records. The table is new and empty, and nothing else moves.
  @Test
  fun version_6_moves_to_7_adding_an_empty_owner_and_keeping_the_rides() {
    helper.createDatabase(DB, 6).apply {
      execSQL("INSERT INTO rides VALUES ('r1', '箱根', 'ended', 'manual', NULL, 5000, 120.0, 6000, 6000, NULL, 0, NULL)")
      close()
    }
    helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7).close()
    val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RideDatabase::class.java, DB)
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
      .allowMainThreadQueries()
      .build()
    runBlocking {
      assertEquals("箱根", db.rides().ride("r1")!!.name)
      assertNull(db.takeover().owner())
    }
    db.close()
  }
}
