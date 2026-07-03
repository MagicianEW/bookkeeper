package com.simplebookkeeper.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.simplebookkeeper.ui.theme.LanguageMode
import com.simplebookkeeper.ui.theme.ThemeMode
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        private val TAG = "SettingsRepository"

        // DataStore keys (保留用于检测是否需要迁移)
        private val WEBDAV_URL = stringPreferencesKey("webdav_url")
        private val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        private val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        private val WEBDAV_ENABLED = booleanPreferencesKey("webdav_enabled")

        // EncryptedSharedPreferences 文件名
        private const val ENCRYPTED_WEBDAV_PREFS_FILE = "webdav_secure_prefs"

        // EncryptedSharedPreferences keys
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USERNAME = "webdav_username"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_WEBDAV_ENABLED = "webdav_enabled"
        private const val KEY_MIGRATED = "migrated_to_encrypted"

        private val CLOUD_SYNC_PROMPT_SHOWN = booleanPreferencesKey("cloud_sync_prompt_shown")
        private val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        private val IS_PASSWORD_SETUP_DONE = booleanPreferencesKey("is_password_setup_done")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val LANGUAGE_MODE = stringPreferencesKey("language_mode")
    }

    // WebDAV 配置（从加密存储读取，带迁移支持）
    val webDavConfig: Flow<WebDavConfig> = context.settingsDataStore.data.map { prefs ->
        // 检查是否已迁移到加密存储
        val encryptedPrefs = getEncryptedWebDavPrefs()
        val migrated = encryptedPrefs?.getBoolean(KEY_MIGRATED, false) ?: false

        if (migrated) {
            // 从加密存储读取
            WebDavConfig(
                url = encryptedPrefs?.getString(KEY_WEBDAV_URL, "") ?: "",
                username = encryptedPrefs?.getString(KEY_WEBDAV_USERNAME, "") ?: "",
                password = encryptedPrefs?.getString(KEY_WEBDAV_PASSWORD, "") ?: "",
                enabled = encryptedPrefs?.getBoolean(KEY_WEBDAV_ENABLED, false) ?: false
            )
        } else {
            // 从旧 DataStore 读取（首次读取时触发迁移）
            WebDavConfig(
                url = prefs[WEBDAV_URL] ?: "",
                username = prefs[WEBDAV_USERNAME] ?: "",
                password = prefs[WEBDAV_PASSWORD] ?: "",
                enabled = prefs[WEBDAV_ENABLED] ?: false
            )
        }
    }

    // 迁移旧凭证到加密存储（如果尚未迁移）
    private suspend fun migrateToEncryptedStorage() {
        try {
            val encryptedPrefs = getEncryptedWebDavPrefs() ?: return
            if (encryptedPrefs.getBoolean(KEY_MIGRATED, false)) {
                return // 已在加密存储中
            }

            // 从旧 DataStore 读取凭证
            val prefs = context.settingsDataStore.data.first()
            val url = prefs[WEBDAV_URL] ?: ""
            val username = prefs[WEBDAV_USERNAME] ?: ""
            val password = prefs[WEBDAV_PASSWORD] ?: ""
            val enabled = prefs[WEBDAV_ENABLED] ?: false

            // 写入加密存储
            if (url.isNotEmpty() || username.isNotEmpty() || password.isNotEmpty()) {
                encryptedPrefs.edit()
                    .putString(KEY_WEBDAV_URL, url)
                    .putString(KEY_WEBDAV_USERNAME, username)
                    .putString(KEY_WEBDAV_PASSWORD, password)
                    .putBoolean(KEY_WEBDAV_ENABLED, enabled)
                    .putBoolean(KEY_MIGRATED, true)
                    .apply()

                // 清除旧 DataStore 中的凭证
                context.settingsDataStore.edit { dataStorePrefs ->
                    dataStorePrefs.remove(WEBDAV_URL)
                    dataStorePrefs.remove(WEBDAV_USERNAME)
                    dataStorePrefs.remove(WEBDAV_PASSWORD)
                    dataStorePrefs.remove(WEBDAV_ENABLED)
                }

                AppLogger.i(TAG, "WebDAV 凭证已迁移到加密存储")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "WebDAV 凭证迁移失败", e)
        }
    }

    private fun getEncryptedWebDavPrefs(): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_WEBDAV_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "创建加密存储失败", e)
            null
        }
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
        // 确保旧凭证已迁移到加密存储
        migrateToEncryptedStorage()

        // 写入加密存储
        val encryptedPrefs = getEncryptedWebDavPrefs()
        if (encryptedPrefs != null) {
            encryptedPrefs.edit()
                .putString(KEY_WEBDAV_URL, config.url)
                .putString(KEY_WEBDAV_USERNAME, config.username)
                .putString(KEY_WEBDAV_PASSWORD, config.password)
                .putBoolean(KEY_WEBDAV_ENABLED, config.enabled)
                .putBoolean(KEY_MIGRATED, true)
                .apply()
            AppLogger.i(TAG, "WebDAV 配置已保存（加密存储）")
        } else {
            // 回退到 DataStore（不应该发生）
            context.settingsDataStore.edit { prefs ->
                prefs[WEBDAV_URL] = config.url
                prefs[WEBDAV_USERNAME] = config.username
                prefs[WEBDAV_PASSWORD] = config.password
                prefs[WEBDAV_ENABLED] = config.enabled
            }
            AppLogger.w(TAG, "WebDAV 配置已保存（DataStore 回退）")
        }
    }

    suspend fun setWebDavEnabled(enabled: Boolean) {
        // 使用加密存储
        val encryptedPrefs = getEncryptedWebDavPrefs()
        if (encryptedPrefs != null && encryptedPrefs.getBoolean(KEY_MIGRATED, false)) {
            encryptedPrefs.edit()
                .putBoolean(KEY_WEBDAV_ENABLED, enabled)
                .apply()
        } else {
            // 回退到 DataStore
            context.settingsDataStore.edit { prefs ->
                prefs[WEBDAV_ENABLED] = enabled
            }
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
