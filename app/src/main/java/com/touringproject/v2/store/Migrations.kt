package com.touringproject.v2.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 -> 2 (slice 2 plan 3a): the asks table, the rides' markers for what the Backend refused, and an index for the
 * points left to send. It only adds, so it stays quick: it runs inside RideController.load(), which the service awaits
 * before startForeground (adr/0006).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `rides` ADD COLUMN `rejectedUpdatedAt` INTEGER")
    db.execSQL("ALTER TABLE `rides` ADD COLUMN `routeRejected` INTEGER NOT NULL DEFAULT 0")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `asks` (`askId` TEXT NOT NULL, `askedAt` INTEGER NOT NULL, `answeredAt` INTEGER, " +
        "`transcript` TEXT, `answer` TEXT, `latitude` REAL, `longitude` REAL, `address` TEXT, `headingDegrees` REAL, " +
        "`input` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `newConversation` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
        "`failure` TEXT, `rideId` TEXT, PRIMARY KEY(`askId`))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_asks_rideId` ON `asks` (`rideId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_asks_askedAt` ON `asks` (`askedAt`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_points_chunk_rideId` ON `route_points` (`chunk`, `rideId`)")
  }
}

/**
 * 2 -> 3 (slice 2 plan 4a, Android #27): a refusal moves from the ride to the points of the refused chunk. Version 2
 * did not keep which chunk was refused, so all the sent points of such a ride count as refused: the ride shows
 * "未送信あり" rather than "保存済み". It only adds, like 1 -> 2.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `route_points` ADD COLUMN `refused` INTEGER NOT NULL DEFAULT 0")
    db.execSQL("UPDATE `route_points` SET `refused` = 1 WHERE `chunk` IS NOT NULL AND `rideId` IN (SELECT `id` FROM `rides` WHERE `routeRejected` = 1)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_points_refused_rideId` ON `route_points` (`refused`, `rideId`)")
  }
}

/**
 * 3 -> 4 (slice 2 plan 4b, adr/0008): asks are taken only while a ride records, and the asks kept without a ride (the
 * test asks of development) go. The schema itself does not change.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("DELETE FROM `asks` WHERE `rideId` IS NULL")
  }
}

/**
 * 4 -> 5 (slice 3 spec §4.1): memos on rides and asks, the point memos and the deletions waiting to be sent. It only
 * adds, like 1 -> 2, so it stays quick inside RideController.load() (adr/0006).
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `rides` ADD COLUMN `memo` TEXT")
    db.execSQL("ALTER TABLE `asks` ADD COLUMN `memo` TEXT")
    db.execSQL("ALTER TABLE `asks` ADD COLUMN `memoUpdatedAt` INTEGER")
    db.execSQL("ALTER TABLE `asks` ADD COLUMN `memoSentUpdatedAt` INTEGER")
    db.execSQL("ALTER TABLE `asks` ADD COLUMN `memoRejectedUpdatedAt` INTEGER")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `rideId` TEXT NOT NULL, `at` INTEGER NOT NULL, `latitude` REAL, " +
        "`longitude` REAL, `sampleLatitude` REAL, `sampleLongitude` REAL, `sampleAt` INTEGER, `text` TEXT NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, `sentUpdatedAt` INTEGER, `rejectedUpdatedAt` INTEGER, `address` TEXT, `headingDegrees` REAL, " +
        "PRIMARY KEY(`id`), FOREIGN KEY(`rideId`) REFERENCES `rides`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_rideId` ON `notes` (`rideId`)")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `deletions` (`kind` TEXT NOT NULL, `id` TEXT NOT NULL, `rideId` TEXT, `deletedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`kind`, `id`))",
    )
  }
}

/**
 * 5 -> 6 (slice 4 spec §4.2): asks that never reached the Backend. Their conversation is null, which SQLite cannot allow
 * on the column in place, so the asks table is made again and its rows copied over. The table is small (one row per
 * ask) and has no foreign keys either way, so this stays quick inside RideController.load() (adr/0006).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `asks_new` (`askId` TEXT NOT NULL, `askedAt` INTEGER NOT NULL, `answeredAt` INTEGER, " +
        "`transcript` TEXT, `answer` TEXT, `latitude` REAL, `longitude` REAL, `address` TEXT, `headingDegrees` REAL, " +
        "`input` TEXT NOT NULL, `conversationId` TEXT, `newConversation` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
        "`failure` TEXT, `rideId` TEXT, `memo` TEXT, `memoUpdatedAt` INTEGER, `memoSentUpdatedAt` INTEGER, " +
        "`memoRejectedUpdatedAt` INTEGER, `updatedAt` INTEGER, `sentUpdatedAt` INTEGER, `rejectedUpdatedAt` INTEGER, " +
        "`sampleLatitude` REAL, `sampleLongitude` REAL, `sampleAt` INTEGER, PRIMARY KEY(`askId`))",
    )
    val kept = "`askId`, `askedAt`, `answeredAt`, `transcript`, `answer`, `latitude`, `longitude`, `address`, `headingDegrees`, " +
      "`input`, `conversationId`, `newConversation`, `status`, `failure`, `rideId`, `memo`, `memoUpdatedAt`, " +
      "`memoSentUpdatedAt`, `memoRejectedUpdatedAt`"
    db.execSQL("INSERT INTO `asks_new` ($kept) SELECT $kept FROM `asks`")
    db.execSQL("DROP TABLE `asks`")
    db.execSQL("ALTER TABLE `asks_new` RENAME TO `asks`")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_asks_rideId` ON `asks` (`rideId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_asks_askedAt` ON `asks` (`askedAt`)")
  }
}
