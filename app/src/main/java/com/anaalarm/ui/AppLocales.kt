package com.anaalarm.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * Per-app language support via the framework's LocaleManager (Android 13+).
 *
 * The in-app language setting drives the voice, recognition, and Ana's reply language on every
 * supported version; from Android 13 it additionally localizes the interface itself. Older
 * versions keep the documented device-locale behavior. Two-way etiquette: a language chosen in
 * the system's per-app settings is never overridden by the stored preference — the stored value
 * is applied only while no explicit per-app selection exists.
 */
object AppLocales {

    /** Maps the stored language code ("en"/"pt") onto a BCP-47 tag. */
    fun tagFor(languageCode: String): String =
        if (languageCode == "pt") "pt-BR" else "en"

    /**
     * Applies [languageCode] as the app's explicit per-app locale where the platform supports
     * it. Safe to call repeatedly and on any version.
     */
    fun apply(languageCode: String, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        runCatching {
            manager.applicationLocales = LocaleList.forLanguageTags(tagFor(languageCode))
        }
    }

    /**
     * Pushes the stored preference only when the user has not picked a per-app language in the
     * system UI (empty locale list means "follow system/default").
     */
    fun applyIfUnset(languageCode: String, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val current = runCatching { manager.applicationLocales }.getOrNull() ?: return
        if (!current.isEmpty) return
        apply(languageCode, context)
    }
}
