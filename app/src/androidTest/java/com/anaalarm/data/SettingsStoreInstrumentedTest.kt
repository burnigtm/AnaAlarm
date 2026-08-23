package com.anaalarm.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.anaalarm.support.TestEnv
import com.anaalarm.ui.avatar.Avatars
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DataStore persistence on the device, including the pasted-key sanitising rules. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class SettingsStoreInstrumentedTest {

    private val store: SettingsStore get() = TestEnv.app.settingsStore

    private fun read(): AppSettings = runBlocking { store.settings.first() }

    @Before
    fun setUp() {
        TestEnv.resetSettings()
    }

    @After
    fun tearDown() {
        TestEnv.resetSettings()
    }

    @Test
    fun factoryDefaultsAreReadBack() {
        val settings = read()
        assertEquals("", settings.apiKey)
        assertEquals("", settings.name)
        assertEquals("en", settings.language)
        assertEquals(emptyList<String>(), settings.habits)
        assertEquals(emptyList<String>(), settings.interests)
        assertEquals(10, settings.sessionMinutes)
        assertEquals(10, settings.snoozeMinutes)
        assertEquals(Pronouns.NEUTRAL, settings.pronouns)
        assertFalse(settings.streamingEnabled)
        assertEquals(Tones.UPBEAT, settings.tone)
        assertEquals(1.05f, settings.voicePitch, 0f)
        assertEquals(1.0f, settings.voiceRate, 0f)
        assertEquals(Avatars.DEFAULT, settings.avatar)
    }

    @Test
    fun everyFieldRoundTripsThroughDisk() = runBlocking {
        store.update(
            apiKey = "sk-instrumented",
            name = "Marina",
            language = "pt",
            habits = listOf("regar as plantas", "fazer café"),
            interests = listOf("fotografia"),
            sessionMinutes = 14,
            snoozeMinutes = 3,
            pronouns = Pronouns.SHE,
            streamingEnabled = true,
            tone = Tones.GENTLE,
            voicePitch = 0.9f,
            voiceRate = 1.1f,
            avatar = Avatars.ZEBRA
        )

        val settings = read()
        assertEquals("sk-instrumented", settings.apiKey)
        assertEquals("Marina", settings.name)
        assertEquals("pt", settings.language)
        assertEquals(listOf("regar as plantas", "fazer café"), settings.habits)
        assertEquals(listOf("fotografia"), settings.interests)
        assertEquals(14, settings.sessionMinutes)
        assertEquals(3, settings.snoozeMinutes)
        assertEquals(Pronouns.SHE, settings.pronouns)
        assertTrue(settings.streamingEnabled)
        assertEquals(Tones.GENTLE, settings.tone)
        assertEquals(0.9f, settings.voicePitch, 0f)
        assertEquals(1.1f, settings.voiceRate, 0f)
        assertEquals(Avatars.ZEBRA, settings.avatar)
    }

    @Test
    fun partialUpdatesLeaveOtherValuesUntouched() = runBlocking {
        store.update(apiKey = "sk-keep", name = "Ana", sessionMinutes = 12, avatar = Avatars.DINO)

        store.update(language = "pt")

        val settings = read()
        assertEquals("sk-keep", settings.apiKey)
        assertEquals("Ana", settings.name)
        assertEquals(12, settings.sessionMinutes)
        assertEquals("pt", settings.language)
        assertEquals(Avatars.DINO, settings.avatar)
    }

    @Test
    fun pastedKeysAreTrimmedAndStrippedOfByteOrderMarks() = runBlocking {
        store.update(apiKey = "\uFEFF  sk-pasted-from-browser \n")
        assertEquals("sk-pasted-from-browser", read().apiKey)

        assertEquals("sk-x", SettingsStore.sanitizeSecret("  \uFEFFsk-x\t\n "))
        assertEquals("", SettingsStore.sanitizeSecret("   "))
    }

    @Test
    fun nameIsTrimmedOnWrite() = runBlocking {
        store.update(name = "   Marina   ")
        assertEquals("Marina", read().name)
    }

    @Test
    fun listsDropBlankEntriesAndTrimWhitespace() = runBlocking {
        store.update(
            habits = listOf(" water the plants ", "", "   ", "make coffee"),
            interests = listOf("books", " ")
        )

        val settings = read()
        assertEquals(listOf("water the plants", "make coffee"), settings.habits)
        assertEquals(listOf("books"), settings.interests)
    }

    @Test
    fun commaSeparatedInputFromTheUiSplitsIntoAList() = runBlocking {
        // SettingsScreen hands the raw text field value straight to update().
        store.update(habits = "water the plants, make coffee ,".split(","))
        assertEquals(listOf("water the plants", "make coffee"), read().habits)
    }

    @Test
    fun settingsFlowEmitsUpdatedValues() = runBlocking {
        store.update(name = "Before")
        assertEquals("Before", store.settings.first().name)

        store.update(name = "After")
        assertEquals("After", store.settings.first().name)
    }

    @Test
    fun anotherStoreInstanceSeesThePersistedValues() = runBlocking {
        store.update(apiKey = "sk-shared", sessionMinutes = 7, avatar = Avatars.ZEBRA)

        val second = SettingsStore(TestEnv.context)
        val settings = second.settings.first()
        assertEquals("sk-shared", settings.apiKey)
        assertEquals(7, settings.sessionMinutes)
        assertEquals(Avatars.ZEBRA, settings.avatar)
    }

    @Test
    fun apiKeyPlaintextIsAbsentFromTheDataStoreFile() = runBlocking {
        val secret = "sk-must-not-appear-on-disk"
        store.update(apiKey = secret)

        val dataStoreFile = TestEnv.context.filesDir
            .resolve("datastore/anaalarm_settings.preferences_pb")
        assertTrue(dataStoreFile.exists())
        val raw = dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(secret))
    }

    @Test
    fun settingsListsHelpersMatchStorageFormat() {
        assertEquals("a,b", SettingsLists.join(listOf(" a ", "b", "  ")))
        assertEquals(listOf("a", "b"), SettingsLists.split("a, b ,"))
        assertTrue(SettingsLists.split(null).isEmpty())
        assertTrue(SettingsLists.split("").isEmpty())
    }

    @Test
    fun unknownAvatarIsRepairedToDefaultOnRead() = runBlocking {
        store.update(avatar = "velociraptor")
        assertEquals(Avatars.DEFAULT, read().avatar)
    }

    @Test
    fun resetSettingsRestoresEveryPersistedField() = runBlocking {
        store.update(
            apiKey = "sk-dirty",
            name = "Dirty",
            language = "pt",
            habits = listOf("x"),
            interests = listOf("y"),
            sessionMinutes = 15,
            snoozeMinutes = 5,
            pronouns = Pronouns.SHE,
            streamingEnabled = true,
            tone = Tones.DRILL,
            voicePitch = 0.8f,
            voiceRate = 1.2f,
            avatar = Avatars.ZEBRA
        )
        TestEnv.resetSettings()
        assertEquals(AppSettings(), read())
    }
}
