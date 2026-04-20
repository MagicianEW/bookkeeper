package com.simplebookkeeper.sync

import android.content.Context
import com.simplebookkeeper.data.DatabaseManager
import com.simplebookkeeper.data.repository.SettingsRepository
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: androidx.work.WorkerParameters
) : androidx.work.CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val config = settingsRepo.webDavConfig.first()

        if (!config.enabled || config.url.isBlank()) return Result.success()

        val dbManager = DatabaseManager(applicationContext)
        val webDavManager = WebDavManager(applicationContext)

        AppLogger.i(TAG, "doWork: 多文件同步")

        // 检查云端是否有数据（通过检查远程文件列表）
        val remoteFiles = webDavManager.getRemoteFileList(config)
        val hasRemoteData = remoteFiles.any { it.endsWith(".db") }
        val hasLocalData = dbManager.getAllYears().isNotEmpty() || dbManager.metaDbFile.exists()

        when {
            // 云端有数据，本地无数据 → 下载
            hasRemoteData && !hasLocalData -> {
                AppLogger.i(TAG, "doWork: 云端有数据，本地无数据，下载")
                return when (val result = webDavManager.downloadMulti(config, dbManager)) {
                    is SyncResult.Success -> Result.success()
                    is SyncResult.Error -> Result.retry()
                    is SyncResult.Conflict -> Result.success()
                    is SyncResult.MultiConflict -> Result.success()
                }
            }
            // 本地有数据 → 上传同步
            hasLocalData -> {
                return when (val result = webDavManager.syncMulti(config, dbManager)) {
                    is SyncResult.Success -> Result.success()
                    is SyncResult.Error -> Result.retry()
                    is SyncResult.Conflict -> Result.success()
                    is SyncResult.MultiConflict -> Result.success()
                }
            }
            else -> return Result.success()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "webdav_sync"

        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun syncNow(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }
}
