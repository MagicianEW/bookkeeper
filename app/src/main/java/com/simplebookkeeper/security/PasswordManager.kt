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

        /**
         * 字节数组 → 小写 hex
         *
         * 注意：不可用 BigInteger/NumberFormat 之类的字符串转数字方案，
         * 那类实现会丢弃前导零字节，导致 salt 还原后与原始值不同。
         */
        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        /**
         * 小写 hex → 字节数组（逐字节解析，严格保留前导零字节）
         *
         * 这是 v0.4.6 修复的关键：旧实现用 BigInteger(hex, 16).toByteArray()，
         * 当随机 salt 的首字节恰为 0x00（约 1/256 概率）时会被截短，
         * 导致 PBKDF2 派生出的密钥与设置密码时不同 → 正确密码也无法通过校验。
         */
        fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "hex 长度必须为偶数: ${hex.length}" }
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                require(hi >= 0 && lo >= 0) { "非法 hex 字符" }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        /**
         * PBKDF2 哈希（当前默认方式，100,000 次迭代）
         * @return Pair(盐的 hex, 哈希的 hex)
         */
        fun hashPasswordPbkdf2(password: String, salt: ByteArray): Pair<String, String> {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
            val hash = factory.generateSecret(spec).encoded
            return Pair(bytesToHex(salt), bytesToHex(hash))
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

    // 设置密码（使用 PBKDF2 + 随机盐）
    suspend fun setPassword(password: String) {
        val salt = generateSalt()
        val (_, hash) = hashPasswordPbkdf2(password, salt)
        // 存储格式: salt:hash（salt 和 hash 都是 hex 编码）
        val storedValue = "${bytesToHex(salt)}:$hash"
        context.dataStore.edit { prefs ->
            prefs[KEY_PASSWORD_HASH] = storedValue
            prefs[KEY_PASSWORD_ENABLED] = true
        }
        savePlainPassword(password)
        AppLogger.i(TAG, "密码已设置（PBKDF2）")
    }

    // 验证密码（兼容旧版 SHA-256，自动升级）
    suspend fun verifyPassword(password: String): Boolean {
        val stored = context.dataStore.data.map { it[KEY_PASSWORD_HASH] ?: "" }.first()
        if (stored.isEmpty()) {
            AppLogger.w(TAG, "验证失败: 无存储的密码哈希")
            return false
        }
        // PBKDF2 格式: salt:hash（当前默认格式）
        if (stored.contains(":")) {
            val parts = stored.split(":", limit = 2)
            if (parts.size == 2) {
                return try {
                    val (computedSalt, computedHash) = hashPasswordPbkdf2(password, hexToBytes(parts[0]))
                    // 恒定时间比较，避免按字节提前返回带来的时序侧信道
                    val matched = MessageDigest.isEqual(
                        computedHash.toByteArray(Charsets.UTF_8),
                        parts[1].toByteArray(Charsets.UTF_8)
                    )
                    if (matched) {
                        AppLogger.i(TAG, "PBKDF2 验证成功")
                    } else {
                        AppLogger.w(TAG, "PBKDF2 验证失败")
                    }
                    matched
                } catch (e: Exception) {
                    AppLogger.e(TAG, "PBKDF2 验证异常", e)
                    false
                }
            }
        }
        // SHA-256 格式（旧版本兼容）- 验证成功后自动升级到 PBKDF2
        if (stored == hashPasswordSha256(password)) {
            AppLogger.i(TAG, "检测到旧版 SHA-256 格式，正在升级到 PBKDF2...")
            upgradePasswordToPbkdf2(password)
            return true
        }
        return false
    }

    // 将旧版 SHA-256 格式升级到 PBKDF2
    private suspend fun upgradePasswordToPbkdf2(password: String) {
        try {
            val salt = generateSalt()
            val (_, hash) = hashPasswordPbkdf2(password, salt)
            val storedValue = "${bytesToHex(salt)}:$hash"
            context.dataStore.edit { prefs ->
                prefs[KEY_PASSWORD_HASH] = storedValue
            }
            AppLogger.i(TAG, "密码已升级到 PBKDF2 格式")
        } catch (e: Exception) {
            AppLogger.e(TAG, "密码升级失败", e)
        }
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

    // PBKDF2 哈希 / hex 编解码已移至 companion object，便于单元测试覆盖

    // SHA-256 哈希（仅用于旧版兼容，自动升级到 PBKDF2）
    @Deprecated("仅用于旧版本兼容，验证后会自动升级到 PBKDF2")
    private fun hashPasswordSha256(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytesToHex(bytes)
    }
}
