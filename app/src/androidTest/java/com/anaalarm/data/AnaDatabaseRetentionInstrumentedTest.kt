package com.anaalarm.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the production Room onOpen callback, rather than only the DAO delete query. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class AnaDatabaseRetentionInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var openedDatabase: AnaDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun tearDown() {
        openedDatabase?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun onOpenPrunesOnlyMessagesOlderThanRetentionWindow() = runBlocking {
        val cutoff = NOW - AnaDatabase.RAW_MESSAGE_RETENTION_MS
        val seedDatabase = Room.databaseBuilder(context, AnaDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        seedDatabase.messageDao().insert(
            MessageEntity(sessionId = SESSION_ID, role = "user", content = "expired", timestamp = cutoff - 1)
        )
        seedDatabase.messageDao().insert(
            MessageEntity(sessionId = SESSION_ID, role = "user", content = "at cutoff", timestamp = cutoff)
        )
        seedDatabase.messageDao().insert(
            MessageEntity(sessionId = SESSION_ID, role = "assistant", content = "recent", timestamp = NOW)
        )
        seedDatabase.close()

        val reopened = Room.databaseBuilder(context, AnaDatabase::class.java, TEST_DATABASE)
            .addCallback(AnaDatabase.rawMessageRetentionCallback { NOW })
            .allowMainThreadQueries()
            .build()
            .also { openedDatabase = it }

        // Force Room to open before querying so this assertion covers Callback.onOpen.
        reopened.openHelper.writableDatabase

        assertEquals(
            listOf("at cutoff", "recent"),
            reopened.messageDao().getSessionMessages(SESSION_ID).map { it.content }
        )
    }

    private companion object {
        const val TEST_DATABASE = "anaalarm-retention-test.db"
        const val SESSION_ID = 81L
        const val NOW = 2_000_000_000_000L
    }
}
