package com.simplebookkeeper

import android.app.Application
import androidx.work.Configuration
import com.simplebookkeeper.data.AppDatabase
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

    val database by lazy { AppDatabase.getInstance(this) }
    val transactionRepository by lazy {
        TransactionRepository(database.transactionDao(), database.categoryDao())
    }
    val settingsRepository by lazy { SettingsRepository(this) }
    val passwordManager by lazy { PasswordManager(this) }
    val biometricAuth by lazy { BiometricAuth(this) }
    val webDavManager by lazy { WebDavManager(this) }

    override fun onCreate() {
        super.onCreate()
        // 初始化日志系统（最优先）
        AppLogger.init(this)
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
