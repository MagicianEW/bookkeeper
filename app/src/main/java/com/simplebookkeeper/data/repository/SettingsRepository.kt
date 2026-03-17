package com.simplebookkeeper.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = false
)

class SettingsRepository(private val context: Context) {

    companion object {
        private val WEBDAV_URL = stringPreferencesKey("webdav_url")
        private val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        private val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        private val WEBDAV_ENABLED = booleanPreferencesKey("webdav_enabled")
        private val CLOUD_SYNC_PROMPT_SHOWN = booleanPreferencesKey("cloud_sync_prompt_shown")
    }

    val webDavConfig: Flow<WebDavConfig> = context.settingsDataStore.data.map { prefs ->
        WebDavConfig(
            url = prefs[WEBDAV_URL] ?: "",
            username = prefs[WEBDAV_USERNAME] ?: "",
            password = prefs[WEBDAV_PASSWORD] ?: "",
            enabled = prefs[WEBDAV_ENABLED] ?: false
        )
    }

    val isCloudSyncPromptShown: Flow<Boolean> = context.settingsDataStore.data
        .map { it[CLOUD_SYNC_PROMPT_SHOWN] ?: false }

    suspend fun saveWebDavConfig(config: WebDavConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[WEBDAV_URL] = config.url
            prefs[WEBDAV_USERNAME] = config.username
            prefs[WEBDAV_PASSWORD] = config.password
            prefs[WEBDAV_ENABLED] = config.enabled
        }
    }

    suspend fun setWebDavEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[WEBDAV_ENABLED] = enabled
        }
    }

    suspend fun markCloudSyncPromptShown() {
        context.settingsDataStore.edit { prefs ->
            prefs[CLOUD_SYNC_PROMPT_SHOWN] = true
        }
    }
}
