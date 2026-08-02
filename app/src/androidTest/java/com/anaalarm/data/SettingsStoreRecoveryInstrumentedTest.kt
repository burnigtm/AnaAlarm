package com.anaalarm.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic recovery coverage for plaintext, corrupt ciphertext, and invalidated keys. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class SettingsStoreRecoveryInstrumentedTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cipher: RecoverableFakeCipher

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = context.preferencesDataStoreFile("settings-recovery-${UUID.randomUUID()}")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        cipher = RecoverableFakeCipher()
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun legacyPlaintextIsEncryptedAndRemoved() = runBlocking {
        dataStore.edit { it[LEGACY_KEY] = "  sk-from-v2  " }
        val realCipher = KeystoreSecretCipher("anaalarm.settings-migration.${UUID.randomUUID()}")
        val store = SettingsStore(dataStore, realCipher)

        try {
            store.migrateLegacyApiKey()

            val raw = dataStore.data.first()
            assertEquals("sk-from-v2", store.settings.first().apiKey)
            assertNull(raw[LEGACY_KEY])
            assertTrue(raw[ENCRYPTED_KEY]?.startsWith("v1:") == true)
            assertFalse(raw[ENCRYPTED_KEY].orEmpty().contains("sk-from-v2"))
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("sk-from-v2"))
        } finally {
            realCipher.resetKey()
        }
    }

    @Test
    fun corruptCiphertextIsReplacedFromLegacyPlaintext() = runBlocking {
        dataStore.edit {
            it[ENCRYPTED_KEY] = "corrupt-payload"
            it[LEGACY_KEY] = "sk-recoverable"
        }
        val store = SettingsStore(dataStore, cipher)

        store.migrateLegacyApiKey()

        assertEquals(1, cipher.resetCount)
        assertEquals("sk-recoverable", store.settings.first().apiKey)
        assertNull(dataStore.data.first()[LEGACY_KEY])
    }

    @Test
    fun corruptCiphertextWithoutFallbackIsDiscardedAndFailsClosed() = runBlocking {
        dataStore.edit { it[ENCRYPTED_KEY] = "corrupt-payload" }
        val store = SettingsStore(dataStore, cipher)

        store.migrateLegacyApiKey()

        val raw = dataStore.data.first()
        assertEquals(1, cipher.resetCount)
        assertEquals("", store.settings.first().apiKey)
        assertNull(raw[ENCRYPTED_KEY])
        assertNull(raw[LEGACY_KEY])
    }

    @Test
    fun invalidatedKeyDuringWriteIsResetAndRetriedOnce() = runBlocking {
        cipher.encryptFailuresRemaining = 1
        val store = SettingsStore(dataStore, cipher)

        store.update(apiKey = "sk-after-invalidation")

        assertEquals(1, cipher.resetCount)
        assertEquals(2, cipher.encryptCalls)
        assertEquals("sk-after-invalidation", store.settings.first().apiKey)
    }

    private class RecoverableFakeCipher : SecretCipher {
        var generation = 0
        var resetCount = 0
        var encryptCalls = 0
        var encryptFailuresRemaining = 0

        override fun encrypt(plainText: String): String {
            encryptCalls++
            if (encryptFailuresRemaining > 0) {
                encryptFailuresRemaining--
                throw IllegalStateException("simulated invalidated Keystore key")
            }
            return "cipher:$generation:${plainText.reversed()}"
        }

        override fun decrypt(encoded: String): String? {
            val parts = encoded.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "cipher") return null
            if (parts[1].toIntOrNull() != generation) return null
            return parts[2].reversed()
        }

        override fun resetKey() {
            resetCount++
            generation++
        }
    }

    private companion object {
        val LEGACY_KEY = stringPreferencesKey("api_key")
        val ENCRYPTED_KEY = stringPreferencesKey("api_key_encrypted")
    }
}
