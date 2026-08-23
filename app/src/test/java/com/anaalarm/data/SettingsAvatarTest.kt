package com.anaalarm.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.anaalarm.ui.avatar.Avatars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsAvatarTest {

    /**
     * The app-wide DataStore singleton persists across every Robolectric test in this JVM,
     * so each test gets its own throwaway store file to prove true defaults and persistence
     * independent of execution order.
     */
    private fun isolatedStore(): SettingsStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "avatar-test-${UUID.randomUUID()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file }
        )
        return SettingsStore(dataStore, KeystoreSecretCipher())
    }

    @Test
    fun `default avatar is the cheetah`() = runTest {
        assertEquals(Avatars.DEFAULT, isolatedStore().settings.first().avatar)
    }

    @Test
    fun `avatar selection persists`() = runTest {
        val store = isolatedStore()

        store.update(avatar = Avatars.DINO)
        assertEquals(Avatars.DINO, store.settings.first().avatar)

        store.update(avatar = Avatars.ZEBRA)
        assertEquals(Avatars.ZEBRA, store.settings.first().avatar)
    }

    @Test
    fun `corrupt stored species is repaired to the default on read`() = runTest {
        val store = isolatedStore()

        // update() writes raw values; the defensive parse happens when settings are read.
        store.update(avatar = "velociraptor")
        assertEquals(Avatars.DEFAULT, store.settings.first().avatar)
    }

    @Test
    fun `partial update does not clobber the buddy`() = runTest {
        val store = isolatedStore()
        store.update(avatar = Avatars.ZEBRA)
        store.update(name = "Ana")
        assertEquals(Avatars.ZEBRA, store.settings.first().avatar)
        assertEquals("Ana", store.settings.first().name)
    }
}
