package com.anaalarm.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Validates migrations against the exported schema shipped by the released v2 app. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class AnaDatabaseMigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AnaDatabase::class.java
    )

    @Test
    fun migrateTwoToCurrentPreservesDataAndCreatesBothMessageIndexes() {
        helper.createDatabase(TEST_DATABASE, 2).apply {
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
            execSQL(
                "INSERT INTO daily_logs (id, date, summary) " +
                    "VALUES (11, '2026-08-01', 'released v2 data')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            4,
            true,
            AnaDatabase.MIGRATION_2_3,
            AnaDatabase.MIGRATION_3_4
        )

        assertEquals("6", migrated.scalar("SELECT hour FROM alarms WHERE id = 7"))
        assertEquals(
            "still here",
            migrated.scalar("SELECT content FROM messages WHERE sessionId = 123")
        )
        assertEquals(
            "released v2 data",
            migrated.scalar("SELECT summary FROM daily_logs WHERE date = '2026-08-01'")
        )
        assertTrue(migrated.hasIndex("index_messages_sessionId_timestamp"))
        assertTrue(migrated.hasIndex("index_messages_timestamp"))
    }

    private fun SupportSQLiteDatabase.scalar(query: String): String? =
        query(query).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun SupportSQLiteDatabase.hasIndex(name: String): Boolean =
        query(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name)
        ).use { it.moveToFirst() }

    private companion object {
        const val TEST_DATABASE = "anaalarm-v2-migration-test.db"
    }
}
