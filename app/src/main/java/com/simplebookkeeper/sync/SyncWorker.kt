package com.simplebookkeeper.sync

import android.content.Context
import androidx.work.*
import com.simplebookkeeper.data.AppDatabase
import com.simplebookkeeper.data.repository.SettingsRepository
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
        if (!dbFile.exists()) return Result.success()

        val webDavManager = WebDavManager(applicationContext)
        return when (val result = webDavManager.upload(config, dbFile)) {
            is SyncResult.Success -> Result.success()
            is SyncResult.Error -> Result.retry()
            is SyncResult.Conflict -> Result.success() // 冲突由UI层处理
        }
    }

    companion object {
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
