package com.simplebookkeeper.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "security_prefs")

class PasswordManager(private val context: Context) {

    companion object {
        private const val TAG = "PasswordManager"
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
        val hash = hashPasswordSha256(password)
        context.dataStore.edit { prefs ->
            prefs[KEY_PASSWORD_HASH] = hash
            prefs[KEY_PASSWORD_ENABLED] = true
        }
        AppLogger.i(TAG, "密码已设置")
    }

    // 验证密码
    suspend fun verifyPassword(password: String): Boolean {
        val stored = context.dataStore.data.map { it[KEY_PASSWORD_HASH] ?: "" }.first()
        if (stored.isEmpty()) {
            AppLogger.w(TAG, "验证失败: 无存储的密码哈希")
            return false
        }
        // PBKDF2 格式: salt:hash（兼容旧版 0.3.7 beta 设置的密码）
        if (stored.contains(":")) {
            val parts = stored.split(":", limit = 2)
            if (parts.size == 2) {
                return try {
                    val (_, computedHash) = hashPasswordPbkdf2(password, hexToBytes(parts[0]))
                    computedHash == parts[1]
                } catch (e: Exception) {
                    AppLogger.e(TAG, "PBKDF2 验证异常", e)
                    false
                }
            }
        }
        // SHA-256 格式（当前默认 + 旧版兼容）
        return stored == hashPasswordSha256(password)
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

    // SHA-256 哈希（默认方式，跨设备、跨版本一致）
    private fun hashPasswordSha256(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytesToHex(bytes)
    }

    // PBKDF2 哈希（仅用于验证旧版 0.3.7 beta 设置的密码）
    private fun hashPasswordPbkdf2(password: String, salt: ByteArray): Pair<String, String> {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        val hash = factory.generateSecret(spec).encoded
        return Pair(bytesToHex(hash), bytesToHex(salt))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        return bytes
    }
}
