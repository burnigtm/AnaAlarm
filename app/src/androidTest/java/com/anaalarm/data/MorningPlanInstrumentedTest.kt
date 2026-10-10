package com.anaalarm.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MorningPlanInstrumentedTest {
    @Before fun before() = TestEnv.resetAll()
    @After fun after() = TestEnv.resetAll()
    @Test fun priorityReplacesOnlyItsDateAndSurvivesDatabaseReload() = runBlocking {
        val memory = TestEnv.app.memoryStore
        val yesterday = LocalDate.now().minusDays(1)
        memory.savePriority("Yesterday", yesterday)
        memory.savePriority("First")
        memory.savePriority("  Updated  ")
        assertEquals("Updated", memory.priority()!!.content)
        assertEquals("Yesterday", memory.priority(yesterday)!!.content)
        assertEquals(2, memory.exportablePriorities().size)
        assertEquals("Updated", AnaDatabase.get(TestEnv.context).dailyPriorityDao().get(LocalDate.now().toString())!!.content)
    }
    @Test fun blankPrioritiesDoNotEraseExistingContentAndLengthIsBounded() = runBlocking {
        TestEnv.app.memoryStore.savePriority("A".repeat(600))
        TestEnv.app.memoryStore.savePriority("  ")
        assertEquals(500, TestEnv.app.memoryStore.priority()!!.content.length)
        TestEnv.app.memoryStore.restorePriority(DailyPriorityEntity("bad-date", "bad", 1))
        assertEquals(1, TestEnv.app.memoryStore.exportablePriorities().size)
    }
    @Test fun encryptedExportRoundTripsSessionModeAndPrioritiesIdempotently() = runBlocking {
        TestEnv.app.settingsStore.update(sessionMode = SessionModes.ROUTINE)
        TestEnv.app.memoryStore.savePriority("Read chapter three")
        val payload = DataExport.deserialize(DataExport.serialize(DataExport.build(TestEnv.app.memoryStore, TestEnv.app.settingsStore)))!!
        TestEnv.clearDatabase(); TestEnv.resetSettings()
        repeat(2) { DataExport.restore(payload, TestEnv.app.memoryStore, TestEnv.app.settingsStore) }
        assertEquals(SessionModes.ROUTINE, TestEnv.app.settingsStore.settings.first().sessionMode)
        assertEquals("Read chapter three", TestEnv.app.memoryStore.priority()!!.content)
        assertEquals(1, TestEnv.app.memoryStore.exportablePriorities().size)
    }
    @Test fun legacyExportLeavesNewSettingsAndPrioritiesIntact() = runBlocking {
        TestEnv.app.settingsStore.update(sessionMode = SessionModes.ROUTINE)
        TestEnv.app.memoryStore.savePriority("Keep me")
        val current = DataExport.build(TestEnv.app.memoryStore, TestEnv.app.settingsStore)
        val legacy = current.copy(settings = current.settings!!.copy(sessionMode = null), priorities = emptyList())
        DataExport.restore(legacy, TestEnv.app.memoryStore, TestEnv.app.settingsStore)
        assertEquals(SessionModes.ROUTINE, TestEnv.app.settingsStore.settings.first().sessionMode)
        assertEquals("Keep me", TestEnv.app.memoryStore.priority()!!.content)
    }
    @Test fun unknownSessionModeRestoresTheConversationDefault() = runBlocking {
        TestEnv.app.settingsStore.update(sessionMode = "future-mode")
        assertEquals(SessionModes.CONVERSATION, TestEnv.app.settingsStore.settings.first().sessionMode)
    }
}
