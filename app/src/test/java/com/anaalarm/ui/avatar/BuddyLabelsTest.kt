package com.anaalarm.ui.avatar

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BuddyLabelsTest {
    @Test fun `all reactions provide distinct localized accessibility descriptions`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val descriptions = listOf("en", "pt-BR").map { tag ->
            val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val context = base.createConfigurationContext(configuration)
            AvatarMood.entries.map { reactionDescription(context, it) }.also { labels ->
                assertEquals(AvatarMood.entries.size, labels.toSet().size)
                assertTrue(labels.all { it.isNotBlank() })
                Avatars.ALL.forEach { species -> assertTrue(buddyContentDescription(context, species).contains(avatarDisplayName(context, species))) }
            }
        }
        assertTrue(descriptions[0].zip(descriptions[1]).all { (english, portuguese) -> english != portuguese })
    }
}
