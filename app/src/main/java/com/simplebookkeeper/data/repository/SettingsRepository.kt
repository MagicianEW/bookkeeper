package com.simplebookkeeper.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.simplebookkeeper.ui.theme.LanguageMode
import com.simplebookkeeper.ui.theme.ThemeMode
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
        private val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        private val IS_PASSWORD_SETUP_DONE = booleanPreferencesKey("is_password_setup_done")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val LANGUAGE_MODE = stringPreferencesKey("language_mode")
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

    /** 是否首次启动（用于弹出密码设置对话框） */
    val isFirstLaunch: Flow<Boolean> = context.settingsDataStore.data
        .map { it[IS_FIRST_LAUNCH] ?: true }

    /** 密码设置流程是否已完成（设置或跳过） */
    val isPasswordSetupDone: Flow<Boolean> = context.settingsDataStore.data
        .map { it[IS_PASSWORD_SETUP_DONE] ?: false }

    /** 主题模式（默认跟随系统） */
    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data
        .map { ThemeMode.fromName(it[THEME_MODE]) }

    /** 语言模式（默认跟随系统） */
    val languageMode: Flow<LanguageMode> = context.settingsDataStore.data
        .map { LanguageMode.fromName(it[LANGUAGE_MODE]) }

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

    /** 标记非首次启动 */
    suspend fun markNotFirstLaunch() {
        context.settingsDataStore.edit { prefs ->
            prefs[IS_FIRST_LAUNCH] = false
        }
    }

    /** 标记密码设置流程已完成 */
    suspend fun markPasswordSetupDone() {
        context.settingsDataStore.edit { prefs ->
            prefs[IS_PASSWORD_SETUP_DONE] = true
        }
    }

    /** 保存主题模式 */
    suspend fun saveThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[THEME_MODE] = mode.name
        }
    }

    /** 保存语言模式 */
    suspend fun saveLanguageMode(mode: LanguageMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[LANGUAGE_MODE] = mode.name
        }
    }
}
