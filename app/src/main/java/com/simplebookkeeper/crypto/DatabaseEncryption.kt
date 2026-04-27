package com.simplebookkeeper.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

/**
 * 数据库加密管理
 *
 * 使用 Android Keystore + EncryptedSharedPreferences 存储加密密钥
 * 数据库通过 SQLCipher SupportFactory 实现透明加密
 */
class DatabaseEncryption(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_db_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_NAME = "db_encryption_key"
        private const val MIGRATION_FLAG = "encryption_migration_done"
    }

    /** 获取或创建 256 位加密密钥 */
    fun getOrCreateKey(): ByteArray {
        val existing = prefs.getString(KEY_NAME, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.DEFAULT)
        }
        val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_NAME, Base64.encodeToString(newKey, Base64.DEFAULT))
            .apply()
        return newKey
    }

    /** 创建 SQLCipher SupportFactory，传给 Room databaseBuilder */
    fun getSupportFactory(): SupportFactory = SupportFactory(getOrCreateKey())

    fun isMigrationDone(): Boolean = prefs.getBoolean(MIGRATION_FLAG, false)

    fun markMigrationDone() {
        prefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
    }

    /** 重置加密迁移标记，用于导入/恢复未加密数据后触发重新迁移 */
    fun resetMigrationFlag() {
        prefs.edit().putBoolean(MIGRATION_FLAG, false).apply()
    }
}
