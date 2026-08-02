package com.anaalarm.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.anaalarm.R
import com.anaalarm.support.TestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Resource-level localisation checks. Runs on device so the real resource table (including
 * the pt-rBR qualifier folder packed into the APK) is what gets exercised.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class LocalizationInstrumentedTest {

    /** Strings that are legitimately identical in both languages. */
    private val sharedByDesign = setOf("app_name", "language_pt")

    private fun localized(locale: Locale): Context {
        val config = Configuration(TestEnv.context.resources.configuration)
        config.setLocale(locale)
        return TestEnv.context.createConfigurationContext(config)
    }

    private fun allStringResources(): List<Pair<String, Int>> =
        R.string::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.name to it.getInt(null) }

    @Test
    fun everyStringResolvesInEnglishAndBrazilianPortuguese() {
        val english = localized(Locale.US)
        val portuguese = localized(Locale.forLanguageTag("pt-BR"))

        val ids = allStringResources()
        assertTrue("no string resources found", ids.isNotEmpty())

        ids.forEach { (name, id) ->
            assertTrue("$name is blank in English", english.getString(id).isNotBlank())
            assertTrue("$name is blank in pt-BR", portuguese.getString(id).isNotBlank())
        }
    }

    @Test
    fun everyUserFacingStringIsActuallyTranslated() {
        val english = localized(Locale.US)
        val portuguese = localized(Locale.forLanguageTag("pt-BR"))

        val untranslated = allStringResources()
            .filter { (name, _) -> name !in sharedByDesign }
            .filter { (_, id) -> english.getString(id) == portuguese.getString(id) }
            .map { it.first }

        assertTrue("missing pt-BR translations: $untranslated", untranslated.isEmpty())
    }

    @Test
    fun formatArgumentsSurviveTranslation() {
        val portuguese = localized(Locale.forLanguageTag("pt-BR"))

        assertTrue(portuguese.getString(R.string.alarm_scheduled_today).contains("%1\$s"))
        assertTrue(portuguese.getString(R.string.alarm_scheduled_tomorrow).contains("%1\$s"))
        val byDay = portuguese.getString(R.string.alarm_scheduled_day)
        assertTrue(byDay.contains("%1\$s"))
        assertTrue(byDay.contains("%2\$s"))

        assertEquals(
            "Alarme definido para hoje às 06:30",
            portuguese.getString(R.string.alarm_scheduled_today, "06:30")
        )
    }

    @Test
    fun keySignalStringsUseTheExpectedWording() {
        val english = localized(Locale.US)
        val portuguese = localized(Locale.forLanguageTag("pt-BR"))

        assertEquals("Good morning!", english.getString(R.string.wake_up_title))
        assertEquals("Bom dia!", portuguese.getString(R.string.wake_up_title))
        assertEquals("Stop", english.getString(R.string.stop))
        assertEquals("Parar", portuguese.getString(R.string.stop))
    }

    @Test
    fun spokenErrorMessagesTellTheUserHowToRecover() {
        val english = localized(Locale.US)

        assertTrue(english.getString(R.string.no_api_key).contains("settings", ignoreCase = true))
        assertTrue(english.getString(R.string.invalid_api_key).contains("platform.deepseek.com"))
        assertFalse(english.getString(R.string.network_error).contains("%"))
    }
}
