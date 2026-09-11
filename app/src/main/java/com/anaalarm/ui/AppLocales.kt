package com.anaalarm.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language support via the framework's LocaleManager (Android 13+).
 *
 * The in-app language setting drives the voice, recognition, and Ana's reply language on every
 * supported version; from Android 13 it additionally localizes the interface itself. Older
 * versions keep the documented device-locale behavior.
 *
 * Applying a locale recreates every activity, so [apply] deliberately no-ops when the requested
 * language is already the effective one — saves without a language change must never bounce the
 * UI, and process starts must not trigger spurious recreations.
 */
object AppLocales {

    /** Maps the stored language code ("en"/"pt") onto a BCP-47 tag. */
    fun tagFor(languageCode: String): String =
        if (languageCode == "pt") "pt-BR" else "en"

    /** True when [languageCode] already matches the effective configuration language. */
    fun isEffective(languageCode: String, context: Context): Boolean {
        val desired = Locale.forLanguageTag(tagFor(languageCode)).language
        val current = context.resources.configuration.locales[0].language
        return desired.equals(current, ignoreCase = true)
    }

    /**
     * Applies [languageCode] as the app's explicit per-app locale where the platform supports
     * it. Safe to call repeatedly and on any version; identical languages are skipped.
     */
    fun apply(languageCode: String, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isEffective(languageCode, context)) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        runCatching {
            manager.applicationLocales = LocaleList.forLanguageTags(tagFor(languageCode))
        }
    }

    /**
     * Process-start helper: reapplies the stored language when it is not already effective.
     * Skips when identical so a cold start never forces an Activity recreation; never overrides
     * an already-matching system/app locale choice.
     */
    fun applyIfUnset(languageCode: String, context: Context) = apply(languageCode, context)
}

