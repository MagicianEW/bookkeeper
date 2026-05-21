package com.simplebookkeeper.sync

import android.content.Context
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.repository.SettingsRepository
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: androidx.work.WorkerParameters
) : androidx.work.CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val config = settingsRepo.webDavConfig.first()

        if (!config.enabled || config.url.isBlank()) return Result.success()

        val app = applicationContext as? BookkeeperApp ?: return Result.retry()
        val webDavManager = app.webDavManager

        AppLogger.i(TAG, "doWork: 开始同步")

        return try {
            // 上传：导出 ZIP → 上传
            val tempFile = File(applicationContext.cacheDir, "sync_export.zip")
            // 从安全存储取出明文密码（未设置密码时为 null，则导出不加密）
            val password: String? = app.passwordManager.getPlainPassword()

            val exportSuccess = DataExporter.exportToZip(applicationContext, tempFile, password)
            if (exportSuccess) {
                val zipBytes = tempFile.readBytes()
                val uploadSuccess = webDavManager.uploadData(zipBytes, config)
                tempFile.delete()
                if (uploadSuccess) {
                    AppLogger.i(TAG, "doWork: 同步成功")
                    Result.success()
                } else {
                    AppLogger.e(TAG, "doWork: 上传失败")
                    Result.retry()
                }
            } else {
                tempFile.delete()
                AppLogger.e(TAG, "doWork: 导出失败")
                Result.retry()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "doWork 异常", e)
            Result.retry()
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
