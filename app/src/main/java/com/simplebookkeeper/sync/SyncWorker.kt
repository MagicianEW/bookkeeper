package com.simplebookkeeper.sync

import android.content.Context
import androidx.work.*
import com.simplebookkeeper.data.DatabaseManager
import com.simplebookkeeper.data.repository.SettingsRepository
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val config = settingsRepo.webDavConfig.first()

        if (!config.enabled || config.url.isBlank()) return Result.success()

        val webDavManager = WebDavManager(applicationContext)

        return when (val result = webDavManager.syncMultiFile(config)) {
            is SyncResult.Success -> {
                AppLogger.i(TAG, "doWork: 多文件同步成功")
                Result.success()
            }
            is SyncResult.Error -> {
                AppLogger.w(TAG, "doWork: 同步失败 - ${result.message}")
                Result.retry()
            }
            is SyncResult.Conflict -> {
                // 单文件冲突，自动以本地为准上传
                AppLogger.i(TAG, "doWork: 检测到冲突，以本地数据为准")
                val uploadResult = webDavManager.downloadAll(config)
                when (uploadResult) {
                    is SyncResult.Success -> Result.success()
                    is SyncResult.Error -> Result.retry()
                    else -> Result.success()
                }
            }
            is SyncResult.MultiConflict -> {
                // 多文件冲突，默认以本地为准
                AppLogger.i(TAG, "doWork: 检测到${result.conflictFiles.size}个文件冲突，以本地数据为准")
                val uploadResult = webDavManager.syncMultiFile(config)
                when (uploadResult) {
                    is SyncResult.Success -> Result.success()
                    is SyncResult.Error -> Result.retry()
                    else -> Result.success()
                }
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
