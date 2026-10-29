package com.touringproject.v2.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// The device's record of rides (slice 2 spec §5.1). Every schema version is exported to app/schemas and is moved
// forward by a Migration; the database is never rebuilt destructively.

@Database(
  entities = [RideEntity::class, SegmentEntity::class, RoutePointEntity::class, AskEntity::class, NoteEntity::class, DeletionEntity::class],
  version = 6,
  exportSchema = true,
)
abstract class RideDatabase : RoomDatabase() {
  abstract fun rides(): RideDao
  abstract fun asks(): AskDao
  abstract fun notes(): NoteDao
  abstract fun takeover(): TakeoverDao

  companion object {
    fun open(context: Context): RideDatabase =
      Room.databaseBuilder(context, RideDatabase::class.java, "rides.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
  }
}
