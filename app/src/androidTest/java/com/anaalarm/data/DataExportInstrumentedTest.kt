package com.anaalarm.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Device Keystore round-trip for DataExport. Complements the Robolectric unit suite and
 * exercises the same serializer path that release minify must keep (see proguard-rules.pro
 * and scripts/ci/verify-minify-data-export.sh).
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class DataExportInstrumentedTest {

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
        DataExport.cipherOverride = null
    }

    @After
    fun tearDown() {
        DataExport.cipherOverride = null
        TestEnv.resetSettings()
        TestEnv.clearDatabase()
    }

    @Test
    fun keystoreSerializeDeserializeRoundTripPreservesPayload() = runBlocking {
        val memory = TestEnv.app.memoryStore
        val settings = TestEnv.app.settingsStore
        settings.update(name = "DeviceExport", language = "pt")
        memory.saveDailyLog("device morning", LocalDate.of(2026, 9, 11))
        memory.recordSession(startedAtMillis = 10L, endedAtMillis = 70_000L, turns = 2)

        val original = DataExport.build(memory, settings)
        val encoded = DataExport.serialize(original)
        val restored = DataExport.deserialize(encoded)

        assertNotNull(restored)
        assertEquals(original, restored)
    }
}
