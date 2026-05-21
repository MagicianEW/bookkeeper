package com.simplebookkeeper.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.simplebookkeeper.crypto.DatabaseEncryption
import com.simplebookkeeper.data.dao.CategoryDao
import com.simplebookkeeper.data.dao.SavingDao
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.PaymentMethod
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * 数据库管理器 — 单一数据库架构
 *
 * 职责：
 * 1. 管理单一 AppDatabase 实例
 * 2. 检测并迁移旧分年数据库（MetaDatabase + YearDatabase → AppDatabase）
 * 3. 检测并执行加密迁移
 * 4. 提供统一的数据访问接口
 */
class DatabaseManager(private val context: Context) {

    companion object {
        private const val TAG = "DatabaseManager"
        private const val MIGRATION_FLAG = "db_migration_done_v3"
        private const val META_DB_NAME = "bookkeeper_meta.db"

        @Volatile
        private var GLOBAL_INSTANCE: DatabaseManager? = null

        fun closeAll() { GLOBAL_INSTANCE?.close() }

        /** 获取数据库文件路径（供外部使用） */
        fun getDbFile(c: Context): File = c.getDatabasePath(AppDatabase.DB_NAME)
    }

    init { GLOBAL_INSTANCE = this }

    // 加密管理器（Android Keystore + EncryptedSharedPreferences）
    private val encryption = DatabaseEncryption(context)
    private val supportFactory by lazy { encryption.getSupportFactory() }
    /** SQLCipher SupportFactory，供外部验证加密数据库可访问性 */
    val cipherFactory get() = supportFactory

    private val _db: AppDatabase by lazy {
        AppDatabase.getInstance(context, supportFactory)
    }
    val db: AppDatabase get() = _db

    val categoryDao: CategoryDao get() = _db.categoryDao()
    val transactionDao: TransactionDao get() = _db.transactionDao()
    val savingDao: SavingDao get() = _db.savingDao()

    val dbFile: File get() = context.getDatabasePath(AppDatabase.DB_NAME)

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            // 1. 迁移旧分年数据库（MetaDatabase + YearDatabase → AppDatabase）
            migrateFromYearDatabases()
            // 2. 验证数据库可正常访问
            verifyDatabase()
        }
    }

    /**
     * 验证数据库可正常打开和查询
     * 如果无法访问（如加密密钥不匹配），捕获异常防止闪退
     */
    private fun verifyDatabase() {
        try {
            _db.openHelper.writableDatabase
            AppLogger.i(TAG, "数据库验证通过")
        } catch (e: Exception) {
            AppLogger.e(TAG, "数据库验证失败（可能密钥不匹配），将重新创建", e)
            AppDatabase.clearInstance()
        }
    }

    /**
     * 获取所有年份（从 transactions 表中 SQL 查询）
     */
    fun getAllYears(): List<Int> {
        return try {
            val db = _db.openHelper.readableDatabase
            val cursor = db.query(
                "SELECT DISTINCT strftime('%Y', date/1000, 'unixepoch', 'localtime') AS year FROM transactions"
            )
            val years = mutableListOf<Int>()
            while (cursor.moveToNext()) {
                cursor.getString(cursor.getColumnIndexOrThrow("year"))?.toIntOrNull()?.let { years.add(it) }
            }
            cursor.close()
            years.sortedDescending()
        } catch (e: Exception) {
            AppLogger.e(TAG, "查询年份失败", e)
            emptyList()
        }
    }

    // ── 旧分年库迁移（MetaDatabase + YearDatabase → AppDatabase）────────────

    private suspend fun migrateFromYearDatabases() {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return

        val dbDir = context.getDatabasePath("_dummy_").parentFile
            ?: context.filesDir.resolve("../databases").takeIf { it.exists() }
            ?: return

        // 检查是否存在旧分年数据库文件
        val metaFile = File(dbDir, META_DB_NAME)
        val yearFiles = dbDir.listFiles { _: File, name: String ->
            name.startsWith("bookkeeper_") && name.matches(Regex("bookkeeper_\\d{4}\\.db$"))
        }?.toList() ?: emptyList()

        if (!metaFile.exists() && yearFiles.isEmpty()) {
            // 没有旧数据库，直接标记完成
            prefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
            return
        }

        AppLogger.i(TAG, "检测到旧分年数据库，开始迁移...")
        try {
            var totalTransactions = 0

            // 1. 迁移分类（从 MetaDatabase）
            if (metaFile.exists()) {
                val categories = readCategoriesFromDb(metaFile)
                if (categories.isNotEmpty()) {
                    val distinct = categories.groupBy { "${it.name}_${it.type}" }
                        .mapValues { it.value.first() }
                        .values
                        .toList()
                    categoryDao.insertAll(distinct)
                    AppLogger.i(TAG, "迁移分类: ${distinct.size} 条")
                }
            }

            // 2. 迁移交易（从各年 YearDatabase）
            for (yearFile in yearFiles) {
                val transactions = readTransactionsFromDb(yearFile)
                if (transactions.isNotEmpty()) {
                    transactions.forEach { transactionDao.insert(it) }
                    totalTransactions += transactions.size
                    AppLogger.i(TAG, "迁移 ${yearFile.name}: ${transactions.size} 条交易")
                }
            }

            // 3. 备份并删除旧文件
            if (metaFile.exists()) {
                metaFile.copyTo(File(metaFile.parentFile, "$META_DB_NAME.bak"), overwrite = true)
                listOf("", "-shm", "-wal").forEach {
                    File(metaFile.parentFile, "$META_DB_NAME$it").delete()
                }
            }
            for (yearFile in yearFiles) {
                yearFile.copyTo(File(yearFile.parentFile, "${yearFile.name}.bak"), overwrite = true)
                listOf("", "-shm", "-wal").forEach {
                    File(yearFile.parentFile, "${yearFile.name}$it").delete()
                }
            }

            prefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
            AppLogger.i(TAG, "旧分年数据库迁移完成，共迁移 $totalTransactions 条交易")
        } catch (e: Exception) {
            AppLogger.e(TAG, "旧分年数据库迁移失败", e)
            // 不标记完成，下次启动可重试
        }
    }

    /**
     * 从数据库文件读取分类（兼容未加密和加密）
     */
    private fun readCategoriesFromDb(file: File): List<Category> {
        return try {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            val list = mutableListOf<Category>()
            val cursor = db.rawQuery("SELECT * FROM categories", null)
            while (cursor.moveToNext()) {
                list.add(
                    Category(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        type = TransactionType.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("type"))
                        ),
                        icon = cursor.getString(cursor.getColumnIndexOrThrow("icon")) ?: "",
                        isDefault = cursor.getInt(cursor.getColumnIndexOrThrow("isDefault")) == 1,
                        sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow("sortOrder"))
                    )
                )
            }
            cursor.close()
            db.close()
            list
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取分类失败（可能是加密文件）: ${file.name}", e)
            emptyList()
        }
    }

    /**
     * 从数据库文件读取交易（兼容未加密和加密）
     */
    private fun readTransactionsFromDb(file: File): List<Transaction> {
        return try {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            val list = mutableListOf<Transaction>()
            val cursor = db.rawQuery("SELECT * FROM transactions ORDER BY date ASC", null)
            while (cursor.moveToNext()) {
                // 兼容 amount 列为 REAL (Double) 或 INTEGER (Long)
                val amountIndex = cursor.getColumnIndexOrThrow("amount")
                val amount = try {
                    cursor.getLong(amountIndex)
                } catch (_: Exception) {
                    (cursor.getDouble(amountIndex) * 100).toLong()
                }
                list.add(
                    Transaction(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        type = TransactionType.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("type"))
                        ),
                        amount = amount,
                        categoryId = cursor.getLong(cursor.getColumnIndexOrThrow("categoryId")),
                        paymentMethod = PaymentMethod.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("paymentMethod"))
                        ),
                        note = cursor.getString(cursor.getColumnIndexOrThrow("note")) ?: "",
                        date = java.util.Date(cursor.getLong(cursor.getColumnIndexOrThrow("date"))),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt"))
                    )
                )
            }
            cursor.close()
            db.close()
            list
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取交易失败（可能是加密文件）: ${file.name}", e)
            emptyList()
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    fun close() {
        AppDatabase.clearInstance()
    }

    /**
     * 重置加密迁移标记并清空 DB 缓存
     * 用于从 WebDAV 恢复或 zip 导入后，触发重新加密迁移
     * 调用后需重启应用
     */
    fun resetForReEncryption() {
        encryption.resetMigrationFlag()
        AppDatabase.clearInstance()
        AppLogger.i(TAG, "已重置加密迁移标记，重启应用后将重新加密")
    }
}
