package com.anaalarm.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataExportTest {

    /** Robolectric has no AndroidKeyStore; a reversible fake keeps the contract testable. */
    private class FakeCipher : SecretCipher {
        private val store = mutableMapOf<String, String>()
        override fun encrypt(plainText: String): String {
            val token = java.util.Base64.getEncoder()
                .encodeToString("E:$plainText".toByteArray(Charsets.UTF_8))
            store[token] = plainText
            return token
        }
        override fun decrypt(encoded: String): String? =
            store[encoded]?.removePrefix("E:")
        override fun resetKey() { /* nothing to reset */ }
    }

    private lateinit var db: AnaDatabase
    private lateinit var memory: MemoryStore
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AnaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsStore = SettingsStore(context)
        memory = MemoryStore(db, settingsStore)
        DataExport.cipherOverride = FakeCipher()
    }

    @After
    fun tearDown() {
        DataExport.cipherOverride = null
        db.close()
    }

    @Test
    fun `exported settings structurally exclude every credential field`() {
        val fields = DataExport.ExportedSettings::class.java.declaredFields.map { it.name }
        val sensitive = fields.filter { field ->
            val lowered = field.lowercase()
            lowered.contains("key") || lowered.contains("secret") || lowered.contains("token")
        }
        assertTrue("credential-like fields leaked: $sensitive", sensitive.isEmpty())
        // And the payload type itself carries no such property to populate.
        assertTrue(!fields.any { it.equals("apiKey", ignoreCase = true) })
    }

    @Test
    fun `serialize and deserialize round trip preserves the payload`() = runTest {
        settingsStore.update(name = "Alex", language = "pt")
        memory.saveDailyLog("morning summary", java.time.LocalDate.of(2026, 8, 20))
        memory.recordSession(startedAtMillis = 1L, endedAtMillis = 61_000L, turns = 3)
        memory.setHabitDone("water plants", java.time.LocalDate.of(2026, 8, 20), true)

        val original = DataExport.build(memory, settingsStore)
        val restored = DataExport.deserialize(DataExport.serialize(original))

        assertEquals(original, restored)
    }

    @Test
    fun `deserialize rejects foreign or damaged files`() {
        assertNull(DataExport.deserialize("not base64 at all!"))
        assertNull(DataExport.deserialize(""))
    }

    @Test
    fun `restore merges logs habits sessions and settings`() = runTest {
        val day = java.time.LocalDate.of(2026, 8, 19)
        val payload = DataExport.Payload(
            exportedAtEpochMs = 1L,
            settings = DataExport.ExportedSettings(
                name = "Imported",
                language = "en",
                habits = listOf("stretch"),
                interests = listOf("books"),
                sessionMinutes = 8,
                snoozeMinutes = 7,
                pronouns = "neutral",
                tone = "gentle",
                voicePitch = 1.2f,
                voiceRate = 0.9f
            ),
            dailyLogs = listOf(DataExport.ExportedLog(date = day.toString(), summary = "old log")),
            sessions = listOf(
                DataExport.ExportedSession(startedAt = 5L, endedAt = 65_000L, durationMs = 60_000, turns = 4)
            ),
            habits = listOf(DataExport.ExportedHabit(name = "stretch", date = day.toString(), done = true))
        )

        val restored = DataExport.restore(payload, memory, settingsStore)

        assertTrue(restored > 0)
        assertEquals("Imported", settingsStore.settings.first().name)
        assertEquals(8, settingsStore.settings.first().sessionMinutes)
        assertEquals("old log", db.dailyLogDao().getByDate(day.toString())?.summary)
        assertEquals(1, memory.recentSessionRecords().size)
        assertTrue(memory.habitsForDate(day).single().done)
    }

    @Test
    fun `buddy selection round-trips through export and restore`() = runTest {
        settingsStore.update(name = "Alex", avatar = com.anaalarm.ui.avatar.Avatars.DINO)

        val original = DataExport.build(memory, settingsStore)
        assertEquals(com.anaalarm.ui.avatar.Avatars.DINO, original.settings?.avatar)

        val restoredPayload = DataExport.deserialize(DataExport.serialize(original))
        assertEquals(original, restoredPayload)

        settingsStore.update(avatar = com.anaalarm.ui.avatar.Avatars.ZEBRA)
        DataExport.restore(restoredPayload!!, memory, settingsStore)
        assertEquals(com.anaalarm.ui.avatar.Avatars.DINO, settingsStore.settings.first().avatar)
    }

    @Test
    fun `exports from before the buddy feature keep the current buddy`() = runTest {
        // A legacy payload serializes no avatar field at all; restoring it must not clobber
        // the buddy the user already picked.
        settingsStore.update(name = "Old", avatar = com.anaalarm.ui.avatar.Avatars.ZEBRA)
        val legacySettings = DataExport.ExportedSettings(
            name = "Old",
            language = "en",
            habits = emptyList(),
            interests = emptyList(),
            sessionMinutes = 10,
            snoozeMinutes = 5,
            pronouns = "neutral",
            tone = "upbeat",
            voicePitch = 1.0f,
            voiceRate = 1.0f,
            avatar = null
        )
        val payload = DataExport.Payload(exportedAtEpochMs = 2L, settings = legacySettings)

        DataExport.restore(payload, memory, settingsStore)

        assertEquals("Old", settingsStore.settings.first().name)
        assertEquals(
            com.anaalarm.ui.avatar.Avatars.ZEBRA,
            settingsStore.settings.first().avatar
        )
    }
}
