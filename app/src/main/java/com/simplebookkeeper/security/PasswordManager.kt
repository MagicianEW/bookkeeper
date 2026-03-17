package com.simplebookkeeper.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "security_prefs")

class PasswordManager(private val context: Context) {

    companion object {
        private val KEY_PASSWORD_HASH = stringPreferencesKey("password_hash")
        private val KEY_PASSWORD_ENABLED = booleanPreferencesKey("password_enabled")
        private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch")
    }

    val isPasswordEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_PASSWORD_ENABLED] ?: false }

    val isBiometricEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_BIOMETRIC_ENABLED] ?: false }

    val isFirstLaunch: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_FIRST_LAUNCH] ?: true }

    // 设置密码
    suspend fun setPassword(password: String) {
        val hash = hashPassword(password)
        context.dataStore.edit { prefs ->
            prefs[KEY_PASSWORD_HASH] = hash
            prefs[KEY_PASSWORD_ENABLED] = true
        }
    }

    // 验证密码
    suspend fun verifyPassword(password: String): Boolean {
        val hash = context.dataStore.data.map { it[KEY_PASSWORD_HASH] ?: "" }.first()
        return hash.isNotEmpty() && hash == hashPassword(password)
    }

    // 禁用密码
    suspend fun disablePassword() {
        context.dataStore.edit { prefs ->
            prefs[KEY_PASSWORD_ENABLED] = false
            prefs[KEY_BIOMETRIC_ENABLED] = false
            prefs.remove(KEY_PASSWORD_HASH)
        }
    }

    // 启用/禁用生物识别
    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BIOMETRIC_ENABLED] = enabled
        }
    }

    // 标记非首次启动
    suspend fun markNotFirstLaunch() {
        context.dataStore.edit { prefs ->
            prefs[KEY_FIRST_LAUNCH] = false
        }
    }

    // 密码哈希（SHA-256）
    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
