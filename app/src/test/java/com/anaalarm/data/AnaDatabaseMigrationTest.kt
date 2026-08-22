package com.anaalarm.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnaDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun `migration one to seven preserves data and creates retention indexes`() = runTest {
        context.deleteDatabase(TEST_DATABASE)
        createVersionOneDatabase().use { helper ->
            helper.writableDatabase.apply {
                execSQL(
                    "INSERT INTO alarms " +
                        "(id, hour, minute, days, snoozeMinutes, enabled) " +
                        "VALUES (7, 6, 45, 62, 8, 1)"
                )
                execSQL(
                    "INSERT INTO messages " +
                        "(id, sessionId, role, content, timestamp) " +
                        "VALUES (9, 123, 'user', 'still here', 456)"
                )
            }
        }

        val migrated = Room.databaseBuilder(context, AnaDatabase::class.java, TEST_DATABASE)
            .addMigrations(
                AnaDatabase.MIGRATION_1_2,
                AnaDatabase.MIGRATION_2_3,
                AnaDatabase.MIGRATION_3_4,
                AnaDatabase.MIGRATION_4_5,
                AnaDatabase.MIGRATION_5_6,
                AnaDatabase.MIGRATION_6_7
            )
            .allowMainThreadQueries()
            .build()

        try {
            // The v1 alarm row survives with the v6 defaults: no challenge, unlimited snoozes.
            val migratedAlarm = migrated.alarmDao().getById(7)!!
            assertEquals(6, migratedAlarm.hour)
            assertEquals(0, migratedAlarm.challengeType)
            assertEquals(0, migratedAlarm.maxSnoozes)
            assertEquals(null, migratedAlarm.ringtoneUri)
            assertEquals(
                "still here",
                migrated.messageDao().getSessionMessages(123).single().content
            )

            assertIndexExists(migrated.openHelper.readableDatabase, "index_daily_logs_date")
            assertIndexExists(
                migrated.openHelper.readableDatabase,
                "index_messages_sessionId_timestamp"
            )
            assertIndexExists(migrated.openHelper.readableDatabase, "index_messages_timestamp")

            // The v5 tables are live after migrating from a version-1 file.
            migrated.usageDao().insert(
                UsageEntity(
                    date = "2026-08-02",
                    sessionId = 123L,
                    inputTokens = 10,
                    outputTokens = 4,
                    cachedTokens = 2,
                    totalTokens = 14,
                    timestamp = 1L
                )
            )
            val summary = migrated.usageDao().summarySince("2026-08-01")
            assertEquals(14L, summary.totalTokens)
            assertEquals(1, summary.requests)

            migrated.sessionRecordDao().insert(
                SessionRecordEntity(startedAt = 1L, endedAt = 5L, durationMs = 4L, turns = 2)
            )
            assertEquals(2, migrated.sessionRecordDao().getRecent(10).single().turns)

            assertIndexExists(migrated.openHelper.readableDatabase, "index_usage_date")

            // The v7 habit ledger is live and enforces one row per (name, date).
            migrated.habitEventDao().upsert(
                HabitEventEntity(name = "water plants", date = "2026-08-02", done = true)
            )
            migrated.habitEventDao().upsert(
                HabitEventEntity(name = "water plants", date = "2026-08-02", done = false)
            )
            assertEquals(
                1,
                migrated.habitEventDao().getByDate("2026-08-02").size
            )
            assertIndexExists(
                migrated.openHelper.readableDatabase,
                "index_habit_events_name_date"
            )
        } finally {
            migrated.close()
        }
    }

    private fun assertIndexExists(database: SupportSQLiteDatabase, name: String) {
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name)
        ).use { cursor ->
            assertNotNull(if (cursor.moveToFirst()) cursor.getString(0) else null)
        }
    }

    private fun createVersionOneDatabase(): SupportSQLiteOpenHelper {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DATABASE)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS alarms (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "hour INTEGER NOT NULL, minute INTEGER NOT NULL, " +
                                "days INTEGER NOT NULL, snoozeMinutes INTEGER NOT NULL, " +
                                "enabled INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS messages (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "sessionId INTEGER NOT NULL, role TEXT NOT NULL, " +
                                "content TEXT NOT NULL, timestamp INTEGER NOT NULL)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                }
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    private companion object {
        const val TEST_DATABASE = "anaalarm-jvm-migration-test.db"
    }
}

