package com.simplebookkeeper.data

import android.content.Context
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * 一次性迁移工具：将旧版单库 bookkeeper.db 拆分为按年分库 + 元数据库
 *
 * 旧: bookkeeper.db (categories + transactions 全部混在一起)
 *  ↓ 拆分
 * 新: bookkeeper_meta.db (categories)
 *     bookkeeper_YYYY.db (每年的 transactions)
 */
object DataMigrator {

    private const val TAG = "DataMigrator"

    /**
     * 执行迁移，返回是否成功
     * @param onProgress 进度回调 (current, total, message)
     */
    suspend fun migrate(
        context: Context,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!DatabaseManager.legacyDbExists(context)) {
                AppLogger.i(TAG, "旧库不存在，无需迁移")
                return@withContext true
            }

            // 如果已有分库文件，说明迁移已执行过，跳过
            val existingYears = DatabaseManager.getAvailableYears(context)
            if (existingYears.isNotEmpty()) {
                AppLogger.i(TAG, "分库文件已存在，跳过迁移")
                // 但旧文件可能还在，检查是否需要重命名备份
                renameLegacyIfNeeded(context)
                return@withContext true
            }

            AppLogger.i(TAG, "开始迁移旧版单库...")
            onProgress(0, 4, "正在读取旧数据库...")

            // 1. 打开旧库读取所有数据
            val oldDb = AppDatabase.getInstance(context)
            val allTransactions = oldDb.transactionDao().getAll()
            val allCategories = try {
                oldDb.categoryDao().getAllSync()
            } catch (e: Exception) {
                emptyList<Category>()
            }

            AppLogger.i(TAG, "旧库数据: ${allTransactions.size} 条交易, ${allCategories.size} 个分类")

            if (allTransactions.isEmpty() && allCategories.isEmpty()) {
                // 空库，直接重命名旧文件
                AppLogger.i(TAG, "旧库为空，直接完成")
                renameLegacyIfNeeded(context)
                return@withContext true
            }

            // 2. 关闭旧库连接
            oldDb.close()
            AppDatabase.clearInstance()

            onProgress(1, 4, "正在按年份拆分数据...")

            // 3. 按年份拆分 transactions 写入各年库
            val transactionsByYear = allTransactions.groupBy { transaction ->
                val cal = Calendar.getInstance()
                cal.time = transaction.date
                cal.get(Calendar.YEAR)
            }

            var totalWritten = 0
            transactionsByYear.forEach { (year, transactions) ->
                AppLogger.i(TAG, "写入年库 $year: ${transactions.size} 条")
                val yearDb = DatabaseManager.getYearDb(context, year)
                val dao = yearDb.transactionDao()
                transactions.forEach { transaction ->
                    dao.insert(transaction)
                }
                totalWritten += transactions.size
            }

            onProgress(2, 4, "正在迁移分类数据...")

            // 4. 复制 categories 到 meta 库
            val metaDb = MetaDatabase.getInstance(context)
            val categoryDao = metaDb.categoryDao()
            // 只在 meta 库为空时插入（避免重复）
            if (categoryDao.count() == 0 && allCategories.isNotEmpty()) {
                categoryDao.insertAll(allCategories)
                AppLogger.i(TAG, "迁移分类: ${allCategories.size} 个")
            } else if (allCategories.isEmpty()) {
                // 旧库没有分类（理论上不会发生），meta 库会在 onCreate 时插入默认分类
            }

            onProgress(3, 4, "正在校验数据完整性...")

            // 5. 校验：逐条计数
            var totalCount = 0
            transactionsByYear.keys.forEach { year ->
                val yearDb = DatabaseManager.getYearDb(context, year)
                val count = yearDb.transactionDao().getAll().size
                totalCount += count
            }

            val metaCount = categoryDao.count()

            AppLogger.i(TAG, "校验结果: 旧库=${allTransactions.size}, 各年库合计=$totalCount, 分类旧=${allCategories.size} 新=$metaCount")

            if (totalCount != allTransactions.size) {
                AppLogger.e(TAG, "校验失败！交易记录数不一致: 旧=${allTransactions.size}, 新=$totalCount")
                // 回滚：删除所有新建的年库文件
                rollback(context)
                return@withContext false
            }

            // 6. 校验通过，重命名旧文件
            renameLegacyIfNeeded(context)

            onProgress(4, 4, "迁移完成！")
            AppLogger.i(TAG, "迁移成功完成")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "迁移异常", e)
            rollback(context)
            false
        }
    }

    /**
     * 将旧版 bookkeeper.db 重命名为 .bak
     */
    private fun renameLegacyIfNeeded(context: Context) {
        val legacyFile = context.getDatabasePath(AppDatabase.DB_NAME)
        if (legacyFile.exists()) {
            val bakFile = File(legacyFile.absolutePath + ".bak")
            val renamed = legacyFile.renameTo(bakFile)
            if (renamed) {
                AppLogger.i(TAG, "旧库已重命名为: ${bakFile.name}")
            } else {
                AppLogger.w(TAG, "旧库重命名失败，尝试删除 WAL/SHM 文件后重试")
                // 删除 WAL 和 SHM 文件后再试
                File(legacyFile.absolutePath + "-wal").delete()
                File(legacyFile.absolutePath + "-shm").delete()
                val retry = legacyFile.renameTo(bakFile)
                AppLogger.i(TAG, "重试重命名: $retry")
            }
        }
    }

    /**
     * 回滚：删除所有新建的分库文件，恢复旧库
     */
    private suspend fun rollback(context: Context) {
        AppLogger.w(TAG, "开始回滚...")
        DatabaseManager.closeAll()
        val yearFiles = DatabaseManager.getAllYearDbFiles(context)
        yearFiles.forEach { file ->
            file.delete()
            File(file.absolutePath + "-wal").delete()
            File(file.absolutePath + "-shm").delete()
            AppLogger.w(TAG, "删除年库: ${file.name}")
        }
        // meta 库如果也是新建的也删除
        val metaFile = DatabaseManager.getMetaDbFile(context)
        if (metaFile.exists()) {
            metaFile.delete()
            File(metaFile.absolutePath + "-wal").delete()
            File(metaFile.absolutePath + "-shm").delete()
        }
        AppLogger.w(TAG, "回滚完成，旧库保留不变")
    }
}
