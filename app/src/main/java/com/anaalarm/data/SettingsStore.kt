package com.anaalarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "anaalarm_settings")

data class AppSettings(
    val apiKey: String = "",
    val name: String = "",
    val language: String = "en",
    val habits: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val sessionMinutes: Int = 10,
    val snoozeMinutes: Int = 10
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val NAME = stringPreferencesKey("name")
        val LANGUAGE = stringPreferencesKey("language")
        val HABITS = stringPreferencesKey("habits")
        val INTERESTS = stringPreferencesKey("interests")
        val SESSION_MINUTES = intPreferencesKey("session_minutes")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            apiKey = p[Keys.API_KEY] ?: "",
            name = p[Keys.NAME] ?: "",
            language = p[Keys.LANGUAGE] ?: "en",
            habits = SettingsLists.split(p[Keys.HABITS]),
            interests = SettingsLists.split(p[Keys.INTERESTS]),
            sessionMinutes = p[Keys.SESSION_MINUTES] ?: 10,
            snoozeMinutes = p[Keys.SNOOZE_MINUTES] ?: 10
        )
    }

    suspend fun update(
        apiKey: String? = null,
        name: String? = null,
        language: String? = null,
        habits: List<String>? = null,
        interests: List<String>? = null,
        sessionMinutes: Int? = null,
        snoozeMinutes: Int? = null
    ) {
        context.dataStore.edit { p ->
            apiKey?.let { p[Keys.API_KEY] = sanitizeSecret(it) }
            name?.let { p[Keys.NAME] = it.trim() }
            language?.let { p[Keys.LANGUAGE] = it }
            habits?.let { p[Keys.HABITS] = SettingsLists.join(it) }
            interests?.let { p[Keys.INTERESTS] = SettingsLists.join(it) }
            sessionMinutes?.let { p[Keys.SESSION_MINUTES] = it }
            snoozeMinutes?.let { p[Keys.SNOOZE_MINUTES] = it }
        }
    }

    companion object {
        /** Trim whitespace/newlines and strip a leading UTF-8 BOM from pasted keys. */
        fun sanitizeSecret(value: String): String =
            value.trim().removePrefix("\uFEFF").trim()
    }
}
