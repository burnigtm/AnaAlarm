package com.anaalarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlarmEntity::class,
        MessageEntity::class,
        DailyLogEntity::class,
        UsageEntity::class,
        SessionRecordEntity::class,
        HabitEventEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AnaDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao
    abstract fun messageDao(): MessageDao
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun usageDao(): UsageDao
    abstract fun sessionRecordDao(): SessionRecordDao
    abstract fun habitEventDao(): HabitEventDao

    companion object {
        @Volatile
        private var instance: AnaDatabase? = null

        fun get(context: Context): AnaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnaDatabase::class.java,
                    "anaalarm.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .addCallback(rawMessageRetentionCallback())
                    .addCallback(usageRetentionCallback())
                    .build()
                    .also { instance = it }
            }

        /** Visible for tests — clears the process singleton. */
        internal fun clearInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        /** Version 2 introduced the per-day conversation memory table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `date` TEXT NOT NULL,
                        `summary` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_logs_date` " +
                        "ON `daily_logs` (`date`)"
                )
            }
        }

        /** Version 3 makes bounded session-history reads efficient as the database grows. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_timestamp` " +
                        "ON `messages` (`sessionId`, `timestamp`)"
                )
            }
        }

        /** Version 4 keeps retention pruning off a full table scan during cold database open. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_timestamp` " +
                        "ON `messages` (`timestamp`)"
                )
            }
        }

        /**
         * Version 5 adds the usage/cost accounting table and completed-session records for the
         * dashboard and future statistics surfaces. Both start empty; no backfill is possible
         * or needed because prior versions never saw token reports.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `date` TEXT NOT NULL,
                        `sessionId` INTEGER,
                        `inputTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `cachedTokens` INTEGER NOT NULL,
                        `totalTokens` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_usage_date` ON `usage` (`date`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `turns` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Version 6 adds per-alarm dismissal behavior: an optional stop challenge, a snooze
         * cap, and a custom ringtone. Defaults keep every existing alarm behaving exactly as
         * before (no challenge, unlimited snoozes, system default sound).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `alarms` ADD COLUMN `challengeType` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `alarms` ADD COLUMN `maxSnoozes` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `ringtoneUri` TEXT")
            }
        }

        /**
         * Version 7 adds the habit ledger behind the home-screen recap card and streak
         * injection into the wake-up prompt. One row per (habit name, date).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `habit_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `done` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_habit_events_name_date` " +
                        "ON `habit_events` (`name`, `date`)"
                )
            }
        }

        /** Prunes abandoned raw transcripts synchronously when Room opens for this process. */
        internal fun rawMessageRetentionCallback(
            nowMillis: () -> Long = System::currentTimeMillis
        ): Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL(
                    "DELETE FROM messages WHERE timestamp < ?",
                    arrayOf<Any>(nowMillis() - RAW_MESSAGE_RETENTION_MS)
                )
            }
        }

        /** Bounds the usage ledger so the dashboard stays small even after years of mornings. */
        internal fun usageRetentionCallback(
            nowMillis: () -> Long = System::currentTimeMillis
        ): Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL(
                    "DELETE FROM usage WHERE timestamp < ?",
                    arrayOf<Any>(nowMillis() - USAGE_RETENTION_MS)
                )
            }
        }

        internal const val RAW_MESSAGE_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        internal const val USAGE_RETENTION_MS = 90L * 24 * 60 * 60 * 1_000
    }
}

