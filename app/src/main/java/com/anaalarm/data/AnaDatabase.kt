package com.anaalarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AlarmEntity::class, MessageEntity::class, DailyLogEntity::class],
    version = 4,
    exportSchema = true
)
abstract class AnaDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao
    abstract fun messageDao(): MessageDao
    abstract fun dailyLogDao(): DailyLogDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .addCallback(rawMessageRetentionCallback())
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

        internal const val RAW_MESSAGE_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
