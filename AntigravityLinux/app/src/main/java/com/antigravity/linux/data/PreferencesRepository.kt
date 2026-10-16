package com.antigravity.linux.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "antigravity_linux_prefs")

data class UserSettings(
    val autoSyncClipboard: Boolean = false,
    val runInBackground: Boolean = true,
    val desktopWidth: Int = 1280,
    val desktopHeight: Int = 720,
    val showModifierBar: Boolean = true,
    val terminalFontSize: Int = 14,
    val notificationEnabled: Boolean = true
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val AUTO_SYNC_CLIPBOARD = booleanPreferencesKey("auto_sync_clipboard")
        val RUN_IN_BACKGROUND = booleanPreferencesKey("run_in_background")
        val DESKTOP_WIDTH = intPreferencesKey("desktop_width")
        val DESKTOP_HEIGHT = intPreferencesKey("desktop_height")
        val SHOW_MODIFIER_BAR = booleanPreferencesKey("show_modifier_bar")
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
        val NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { preferences ->
        UserSettings(
            autoSyncClipboard = preferences[Keys.AUTO_SYNC_CLIPBOARD] ?: false,
            runInBackground = preferences[Keys.RUN_IN_BACKGROUND] ?: true,
            desktopWidth = preferences[Keys.DESKTOP_WIDTH] ?: 1280,
            desktopHeight = preferences[Keys.DESKTOP_HEIGHT] ?: 720,
            showModifierBar = preferences[Keys.SHOW_MODIFIER_BAR] ?: true,
            terminalFontSize = preferences[Keys.TERMINAL_FONT_SIZE] ?: 14,
            notificationEnabled = preferences[Keys.NOTIFICATION_ENABLED] ?: true
        )
    }

    suspend fun updateAutoSyncClipboard(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AUTO_SYNC_CLIPBOARD] = enabled
        }
    }

    suspend fun updateRunInBackground(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.RUN_IN_BACKGROUND] = enabled
        }
    }

    suspend fun updateDesktopResolution(width: Int, height: Int) {
        context.dataStore.edit { preferences ->
            preferences[Keys.DESKTOP_WIDTH] = width
            preferences[Keys.DESKTOP_HEIGHT] = height
        }
    }

    suspend fun updateShowModifierBar(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SHOW_MODIFIER_BAR] = show
        }
    }

    suspend fun updateTerminalFontSize(size: Int) {
        context.dataStore.edit { preferences ->
            preferences[Keys.TERMINAL_FONT_SIZE] = size
        }
    }
}
