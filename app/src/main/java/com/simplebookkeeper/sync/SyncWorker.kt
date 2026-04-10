package com.simplebookkeeper.sync

import android.content.Context
import androidx.work.*
import com.simplebookkeeper.data.AppDatabase
import com.simplebookkeeper.data.repository.SettingsRepository
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val config = settingsRepo.webDavConfig.first()

        if (!config.enabled || config.url.isBlank()) return Result.success()

        val dbFile = applicationContext.getDatabasePath(AppDatabase.DB_NAME)
        val webDavManager = WebDavManager(applicationContext)

        // 检查云端是否有数据
        val remoteExists = webDavManager.remoteFileExists(config)
        val localExists = dbFile.exists() && dbFile.length() > 0

        when {
            // 云端有数据，本地无数据 → 下载
            remoteExists && !localExists -> {
                AppLogger.i(TAG, "doWork: 云端有数据，本地无数据，下载")
                val tempFile = File(applicationContext.cacheDir, "download_tmp.db")
                return when (val result = webDavManager.download(config, tempFile)) {
                    is SyncResult.Success -> {
                        // 关闭数据库连接后替换文件
                        try {
                            AppDatabase.getInstance(applicationContext).close()
                            tempFile.copyTo(dbFile, overwrite = true)
                            tempFile.delete()
                            AppLogger.i(TAG, "doWork: 下载成功，已替换本地数据库")
                            Result.success()
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "doWork: 替换数据库失败", e)
                            Result.retry()
                        }
                    }
                    is SyncResult.Error -> Result.retry()
                    is SyncResult.Conflict -> Result.success()
                }
            }
            // 本地有数据 → 使用智能同步（比较时间戳）
            localExists -> {
                return when (val result = webDavManager.sync(config, dbFile)) {
                    is SyncResult.Success -> Result.success()
                    is SyncResult.Error -> Result.retry()
                    is SyncResult.Conflict -> {
                        // 云端更新，下载覆盖本地
                        AppLogger.i(TAG, "doWork: 云端更新，下载覆盖")
                        val tempFile = File(applicationContext.cacheDir, "download_tmp.db")
                        when (val dlResult = webDavManager.download(config, tempFile)) {
                            is SyncResult.Success -> {
                                try {
                                    AppDatabase.getInstance(applicationContext).close()
                                    tempFile.copyTo(dbFile, overwrite = true)
                                    tempFile.delete()
                                    Result.success()
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "doWork: 冲突下载失败", e)
                                    Result.retry()
                                }
                            }
                            is SyncResult.Error -> Result.retry()
                            is SyncResult.Conflict -> Result.success()
                        }
                    }
                }
            }
            // 云端无数据，本地无数据 → 无操作
            else -> {
                AppLogger.i(TAG, "doWork: 云端和本地都无数据，跳过")
                return Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "webdav_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        // 立即触发一次同步
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
