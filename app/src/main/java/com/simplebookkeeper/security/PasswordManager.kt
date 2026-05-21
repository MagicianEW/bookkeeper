package com.simplebookkeeper.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "security_prefs")

class PasswordManager(private val context: Context) {

    companion object {
        private const val TAG = "PasswordManager"
        private val KEY_PASSWORD_HASH = stringPreferencesKey("password_hash")
        private val KEY_PASSWORD_ENABLED = booleanPreferencesKey("password_enabled")
        private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch")

        // EncryptedSharedPreferences 用于存储明文密码（供后台同步使用）
        private const val ENCRYPTED_PREFS_FILE = "secure_sync_prefs"
        private const val KEY_PLAIN_PASSWORD = "plain_password"

        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH = 256
        private const val SALT_LENGTH = 16
        private const val AES_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        /** PBKDF2 派生 AES-256 密钥（用于 ZIP 加密） */
        fun deriveKey(password: String, salt: ByteArray): SecretKey {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        }

        /** 生成随机 salt */
        fun generateSalt(): ByteArray {
            val salt = ByteArray(SALT_LENGTH)
            SecureRandom().nextBytes(salt)
            return salt
        }

        /** AES/GCM/NoPadding 加密，返回 iv + ciphertext */
        fun encryptData(data: ByteArray, key: SecretKey): ByteArray {
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(data)
            return iv + encrypted
        }

        /** AES/GCM/NoPadding 解密，输入为 iv + ciphertext */
        fun decryptData(data: ByteArray, key: SecretKey): ByteArray {
            if (data.size < GCM_IV_LENGTH) {
                throw IllegalArgumentException("加密数据太短，无法提取 IV")
            }
            val iv = data.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            return cipher.doFinal(ciphertext)
        }
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
        savePlainPassword(password)
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
        removePlainPassword()
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

    // ─── 明文密码（供后台同步使用）─────────────────────────────

    /** 将密码明文写入 EncryptedSharedPreferences，由 Android Keystore 保护 */
    fun savePlainPassword(password: String) {
        try {
            getEncryptedPrefs().edit().putString(KEY_PLAIN_PASSWORD, password).apply()
            AppLogger.i(TAG, "明文密码已写入安全存储")
        } catch (e: Exception) {
            AppLogger.e(TAG, "savePlainPassword 失败", e)
        }
    }

    /** 读取明文密码；未设置密码时返回 null */
    fun getPlainPassword(): String? {
        return try {
            getEncryptedPrefs().getString(KEY_PLAIN_PASSWORD, null)
        } catch (e: Exception) {
            AppLogger.e(TAG, "getPlainPassword 失败", e)
            null
        }
    }

    /** 删除已存储的明文密码（禁用密码时调用） */
    fun removePlainPassword() {
        try {
            getEncryptedPrefs().edit().remove(KEY_PLAIN_PASSWORD).apply()
            AppLogger.i(TAG, "明文密码已从安全存储删除")
        } catch (e: Exception) {
            AppLogger.e(TAG, "removePlainPassword 失败", e)
        }
    }

    private fun getEncryptedPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
