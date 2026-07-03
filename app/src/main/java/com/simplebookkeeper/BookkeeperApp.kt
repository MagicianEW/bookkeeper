package com.simplebookkeeper

import android.app.Application
import androidx.work.Configuration
import com.simplebookkeeper.data.DatabaseManager
import com.simplebookkeeper.data.repository.SavingRepository
import com.simplebookkeeper.data.repository.SettingsRepository
import com.simplebookkeeper.data.repository.TransactionRepository
import com.simplebookkeeper.security.BiometricAuth
import com.simplebookkeeper.security.PasswordManager
import com.simplebookkeeper.sync.SyncWorker
import com.simplebookkeeper.sync.WebDavManager
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BookkeeperApp : Application(), Configuration.Provider {

    /** 数据库管理器（单一数据库架构） */
    val dbManager by lazy { DatabaseManager(this) }

    /** 交易仓库 */
    val transactionRepository by lazy {
        TransactionRepository(dbManager)
    }

    /** 储蓄仓库 */
    val savingRepository by lazy {
        SavingRepository(dbManager.savingDao)
    }

    /** 设置仓库 */
    val settingsRepository by lazy { SettingsRepository(this) }

    /** 应用锁密码管理器（同时用于 ZIP 加密） */
    val passwordManager by lazy { PasswordManager(this) }

    val biometricAuth by lazy { BiometricAuth(this) }
    val webDavManager by lazy { WebDavManager(this) }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)

        // 初始化数据库（迁移检查 + 验证）
        CoroutineScope(Dispatchers.IO).launch {
            dbManager.initialize()
        }

        // 启动时根据配置决定是否调度后台同步
        CoroutineScope(Dispatchers.IO).launch {
            val config = settingsRepository.webDavConfig.first()
            if (config.enabled && config.url.isNotBlank()) {
                SyncWorker.schedule(this@BookkeeperApp)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
