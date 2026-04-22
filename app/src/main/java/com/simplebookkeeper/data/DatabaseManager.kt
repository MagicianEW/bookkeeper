package com.simplebookkeeper.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.simplebookkeeper.data.dao.CategoryDao
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.PaymentMethod
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 数据库管理器 — 按年拆分架构核心
 *
 * 职责：
 * 1. 管理所有年份数据库实例（懒加载）
 * 2. 检测并执行首次迁移（旧单库 → 多库）
 * 3. 提供统一的数据访问接口
 * 4. 自动创建新年份数据库
 */
class DatabaseManager(private val context: Context) {

    companion object {
        private const val TAG = "DatabaseManager"
        private const val OLD_DB_NAME = "bookkeeper.db"
        private const val MIGRATION_FLAG = "db_migration_done_v2"

        @Volatile
        private var GLOBAL_INSTANCE: DatabaseManager? = null

        fun getMetaDbFile(c: Context): File = c.getDatabasePath(MetaDatabase.DB_NAME)

        fun getYearDbFileStatic(c: Context, year: Int): File = c.getDatabasePath(YearDatabase.dbName(year))

        fun getAllYearDbFilesStatic(c: Context): List<File> {
            val dir = c.getDatabasePath("_dummy_").parentFile
                ?: c.filesDir.resolve("../databases").takeIf { it.exists() }
                ?: return emptyList()
            return dir.listFiles { _: File, name: String ->
                name.startsWith("bookkeeper_") && name.matches(Regex("bookkeeper_\\d{4}\\.db$"))
            }?.toList() ?: emptyList()
        }

        fun closeAll() { GLOBAL_INSTANCE?.close() }
    }

    init { GLOBAL_INSTANCE = this }

    private val _metaDb: MetaDatabase by lazy { MetaDatabase.getInstance(context) }
    val metaDb: MetaDatabase get() = _metaDb
    val categoryDao: CategoryDao get() = _metaDb.categoryDao()

    private val yearDbs = ConcurrentHashMap<Int, YearDatabase>()
    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState = _migrationState.asStateFlow()

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            getOrCreateYearDb(currentYear())
            if (needsMigration()) {
                _migrationState.value = MigrationState.InProgress
                performMigration()
                _migrationState.value = MigrationState.Done
            } else {
                _migrationState.value = MigrationState.Done
            }
        }
    }

    fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    fun getYearDao(year: Int): TransactionDao = getOrCreateYearDb(year).transactionDao()

    private fun getOrCreateYearDb(year: Int): YearDatabase =
        yearDbs.getOrPut(year) {
            AppLogger.i(TAG, "创建年份数据库: $year")
            YearDatabase.create(context, year)
        }

    fun getCurrentYearDao(): TransactionDao = getYearDao(currentYear())

    fun getAllYears(): List<Int> {
        val dir = context.getDatabasePath("_dummy_").parentFile
            ?: context.filesDir.resolve("../databases").takeIf { it.exists() }
            ?: return emptyList()
        return dir.listFiles { _: File, name: String ->
            name.startsWith("bookkeeper_") && name.matches(Regex("bookkeeper_\\d{4}\\.db$"))
        }?.mapNotNull { it.name.removePrefix("bookkeeper_").removeSuffix(".db").toIntOrNull() }
            ?.sortedDescending() ?: emptyList()
    }

    // 实例级文件路径（供 WebDavManager syncMulti 使用）
    val metaDbFile: File get() = context.getDatabasePath(MetaDatabase.DB_NAME)
    fun getYearDbFile(year: Int): File = context.getDatabasePath(YearDatabase.dbName(year))
    fun getAllYearDbFiles(): List<File> = getAllYears().map { getYearDbFile(it) }

    // 数据操作
    suspend fun insertTransaction(t: Transaction): Long =
        getYearDao(yearFromDate(t.date.time)).insert(t)

    suspend fun updateTransaction(t: Transaction) =
        getYearDao(yearFromDate(t.date.time)).update(t)

    suspend fun deleteTransaction(t: Transaction) =
        getYearDao(yearFromDate(t.date.time)).delete(t)

    suspend fun deleteTransactionById(id: Long, year: Int) =
        getYearDao(year).deleteById(id)

    suspend fun getTransactionById(id: Long): Transaction? =
        getAllYears().firstNotNullOfOrNull { year -> getYearDao(year).getById(id) }

    // 迁移
    private fun needsMigration(): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return false
        return context.getDatabasePath(OLD_DB_NAME).exists()
    }

    private suspend fun performMigration() = withContext(Dispatchers.IO) {
        val oldFile = context.getDatabasePath(OLD_DB_NAME)
        if (!oldFile.exists()) return@withContext
        AppLogger.i(TAG, "迁移: ${oldFile.absolutePath}")

        try {
            val oldDb = SQLiteDatabase.openDatabase(oldFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

            // ── 迁移分类 ──
            val catCursor = oldDb.rawQuery("SELECT * FROM categories", null)
            val categories = mutableListOf<com.simplebookkeeper.data.model.Category>()
            while (catCursor.moveToNext()) {
                val id = catCursor.getLong(catCursor.getColumnIndexOrThrow("id"))
                val name = catCursor.getString(catCursor.getColumnIndexOrThrow("name"))
                val typeStr = catCursor.getString(catCursor.getColumnIndexOrThrow("type"))
                val icon = catCursor.getString(catCursor.getColumnIndexOrThrow("icon")) ?: ""
                val isDefault = catCursor.getInt(catCursor.getColumnIndexOrThrow("isDefault")) == 1
                val sortOrder = catCursor.getInt(catCursor.getColumnIndexOrThrow("sortOrder"))
                categories.add(
                    com.simplebookkeeper.data.model.Category(
                        id = id, name = name,
                        type = TransactionType.valueOf(typeStr),
                        icon = icon, isDefault = isDefault, sortOrder = sortOrder
                    )
                )
            }
            catCursor.close()
            if (categories.isNotEmpty()) {
                categoryDao.insertAll(categories)
                AppLogger.i(TAG, "迁移分类: ${categories.size} 条")
            }

            // ── 迁移交易 ──
            val cursor = oldDb.rawQuery("SELECT * FROM transactions ORDER BY date ASC", null)
            val transactions = mutableListOf<Transaction>()

            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val typeStr = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                val amountYuan = cursor.getDouble(cursor.getColumnIndexOrThrow("amount"))
                val amountInCents = (amountYuan * 100).toLong()
                val categoryId = cursor.getLong(cursor.getColumnIndexOrThrow("categoryId"))
                val paymentStr = cursor.getString(cursor.getColumnIndexOrThrow("paymentMethod"))
                val note = cursor.getString(cursor.getColumnIndexOrThrow("note")) ?: ""
                val dateMs = cursor.getLong(cursor.getColumnIndexOrThrow("date"))
                val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt"))
                val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt"))

                transactions.add(
                    Transaction(
                        id = id,
                        type = TransactionType.valueOf(typeStr),
                        amount = amountInCents,
                        categoryId = categoryId,
                        paymentMethod = PaymentMethod.valueOf(paymentStr),
                        note = note,
                        date = java.util.Date(dateMs),
                        createdAt = createdAt,
                        updatedAt = updatedAt
                    )
                )
            }
            cursor.close()
            oldDb.close()

            AppLogger.i(TAG, "旧库读取: ${transactions.size} 条")

            val byYear = transactions.groupBy { yearFromDate(it.date.time) }
            for ((year, txns) in byYear) {
                getOrCreateYearDb(year)
                val dao = getYearDao(year)
                txns.forEach { dao.insert(it) }
                AppLogger.i(TAG, "写入 $year: ${txns.size} 条")
            }

            val total = byYear.values.sumOf { it.size }
            if (total != transactions.size)
                throw IllegalStateException("迁移验证失败")

            val backupFile = File(oldFile.parentFile, "bookkeeper.db.bak")
            oldFile.copyTo(backupFile, overwrite = true)
            oldFile.delete()
            AppLogger.i(TAG, "备份: ${backupFile.name}")

            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean(MIGRATION_FLAG, true).apply()
            AppLogger.i(TAG, "迁移完成")

        } catch (e: Exception) {
            AppLogger.e(TAG, "迁移失败", e)
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean(MIGRATION_FLAG, true).apply()
        }
    }

    private fun yearFromDate(timeMs: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        return cal.get(Calendar.YEAR)
    }

    fun close() {
        yearDbs.values.forEach { it.close() }
        yearDbs.clear()
    }

    /**
     * 关闭并移除指定年份的数据库缓存
     * 导入/下载覆盖 .db 文件后调用，让下次访问时 Room 重新打开并执行迁移
     */
    fun invalidateYearDb(year: Int) {
        yearDbs.remove(year)?.close()
    }

    /**
     * 关闭所有年份缓存（导入/下载后调用）
     */
    fun invalidateAllYearDbs() {
        yearDbs.values.forEach { it.close() }
        yearDbs.clear()
    }

    sealed class MigrationState {
        object Idle : MigrationState()
        object InProgress : MigrationState()
        object Done : MigrationState()
        data class Error(val message: String) : MigrationState()
    }
}
