package com.simplebookkeeper.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.simplebookkeeper.crypto.DatabaseEncryption
import com.simplebookkeeper.data.dao.CategoryDao
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Category
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
 * 3. 检测并执行加密迁移（未加密 → SQLCipher 加密）
 * 4. 提供统一的数据访问接口
 * 5. 自动创建新年份数据库
 */
class DatabaseManager(private val context: Context) {

    companion object {
        private const val TAG = "DatabaseManager"
        private const val OLD_DB_NAME = "bookkeeper.db"
        private const val MIGRATION_FLAG = "db_migration_done_v2"

        @Volatile
        private var GLOBAL_INSTANCE: DatabaseManager? = null

        fun getMetaDbFile(c: Context): File = c.getDatabasePath(MetaDatabase.DB_NAME)

        fun getYearDbFileStatic(c: Context, year: Int): File =
            c.getDatabasePath(YearDatabase.dbName(year))

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

    // 加密管理器（Android Keystore + EncryptedSharedPreferences）
    private val encryption = DatabaseEncryption(context)
    private val supportFactory by lazy { encryption.getSupportFactory() }

    private val _metaDb: MetaDatabase by lazy {
        MetaDatabase.getInstance(context, supportFactory)
    }
    val metaDb: MetaDatabase get() = _metaDb
    val categoryDao: CategoryDao get() = _metaDb.categoryDao()

    private val yearDbs = ConcurrentHashMap<Int, YearDatabase>()
    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState = _migrationState.asStateFlow()

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            // 1. 旧单库 → 多库迁移（v1 未加密）
            if (needsLegacyMigration()) {
                _migrationState.value = MigrationState.InProgress
                performLegacyMigration()
            }
            // 2. 未加密多库 → 加密多库迁移
            if (needsEncryptionMigration()) {
                _migrationState.value = MigrationState.InProgress
                performEncryptionMigration()
            }
            _migrationState.value = MigrationState.Done
            // 3. 扫描并打开所有已存在的年份数据库
            scanAndOpenAllYears()
        }
    }

    private fun scanAndOpenAllYears() {
        val existingYears = getAllYears()
        if (existingYears.isNotEmpty()) {
            AppLogger.i(TAG, "扫描到已存在年份数据库: $existingYears")
            existingYears.forEach { getOrCreateYearDb(it) }
        } else {
            getOrCreateYearDb(currentYear())
        }
    }

    fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    fun getYearDao(year: Int): TransactionDao = getOrCreateYearDb(year).transactionDao()

    private fun getOrCreateYearDb(year: Int): YearDatabase =
        yearDbs.getOrPut(year) {
            AppLogger.i(TAG, "创建年份数据库: $year")
            YearDatabase.create(context, year, supportFactory)
        }

    fun getCurrentYearDao(): TransactionDao = getYearDao(currentYear())

    fun getAllYears(): List<Int> {
        val dir = context.getDatabasePath("_dummy_").parentFile
            ?: context.filesDir.resolve("../databases").takeIf { it.exists() }
            ?: return emptyList()
        return dir.listFiles { _: File, name: String ->
            name.startsWith("bookkeeper_") && name.matches(Regex("bookkeeper_\\d{4}\\.db$"))
        }?.mapNotNull {
            it.name.removePrefix("bookkeeper_").removeSuffix(".db").toIntOrNull()
        }?.sortedDescending() ?: emptyList()
    }

    val metaDbFile: File get() = context.getDatabasePath(MetaDatabase.DB_NAME)
    fun getYearDbFile(year: Int): File = context.getDatabasePath(YearDatabase.dbName(year))
    fun getAllYearDbFiles(): List<File> = getAllYears().map { getYearDbFile(it) }

    // ── 数据操作 ──────────────────────────────────────────────────────────────

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

    // ── 旧单库迁移（bookkeeper.db → 多库，无加密）────────────────────────────

    private fun needsLegacyMigration(): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return false
        return context.getDatabasePath(OLD_DB_NAME).exists()
    }

    private suspend fun performLegacyMigration() = withContext(Dispatchers.IO) {
        val oldFile = context.getDatabasePath(OLD_DB_NAME)
        if (!oldFile.exists()) return@withContext
        AppLogger.i(TAG, "旧单库迁移: ${oldFile.absolutePath}")
        try {
            val oldDb = SQLiteDatabase.openDatabase(
                oldFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            // 分类
            val catCursor = oldDb.rawQuery("SELECT * FROM categories", null)
            val categories = mutableListOf<Category>()
            while (catCursor.moveToNext()) {
                categories.add(
                    Category(
                        id = catCursor.getLong(catCursor.getColumnIndexOrThrow("id")),
                        name = catCursor.getString(catCursor.getColumnIndexOrThrow("name")),
                        type = TransactionType.valueOf(
                            catCursor.getString(catCursor.getColumnIndexOrThrow("type"))
                        ),
                        icon = catCursor.getString(catCursor.getColumnIndexOrThrow("icon")) ?: "",
                        isDefault = catCursor.getInt(catCursor.getColumnIndexOrThrow("isDefault")) == 1,
                        sortOrder = catCursor.getInt(catCursor.getColumnIndexOrThrow("sortOrder"))
                    )
                )
            }
            catCursor.close()
            // 按 name+type 去重，防止旧库本身或迁移重跑时产生重复
            if (categories.isNotEmpty()) {
                val distinct = categories.groupBy { "${it.name}_${it.type}" }
                    .mapValues { it.value.first() }
                    .values
                    .toList()
                categoryDao.insertAll(distinct)
            }

            // 交易
            val cursor = oldDb.rawQuery("SELECT * FROM transactions ORDER BY date ASC", null)
            val transactions = mutableListOf<Transaction>()
            while (cursor.moveToNext()) {
                transactions.add(
                    Transaction(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        type = TransactionType.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("type"))
                        ),
                        amount = (cursor.getDouble(cursor.getColumnIndexOrThrow("amount")) * 100).toLong(),
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
            oldDb.close()

            val byYear = transactions.groupBy { yearFromDate(it.date.time) }
            for ((year, txns) in byYear) {
                val dao = getYearDao(year)
                txns.forEach { dao.insert(it) }
                AppLogger.i(TAG, "写入 $year: ${txns.size} 条")
            }

            // 备份旧库
            val backup = File(oldFile.parentFile, "bookkeeper.db.bak")
            oldFile.copyTo(backup, overwrite = true)
            oldFile.delete()

            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean(MIGRATION_FLAG, true).apply()
            AppLogger.i(TAG, "旧单库迁移完成")
        } catch (e: Exception) {
            AppLogger.e(TAG, "旧单库迁移失败", e)
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean(MIGRATION_FLAG, true).apply()
        }
    }

    // ── 加密迁移（未加密多库 → SQLCipher 加密多库）───────────────────────────

    /**
     * 检测是否需要加密迁移：
     * - 加密迁移标记未设置
     * - 存在可被标准 SQLite 打开的数据库文件（即未加密）
     */
    private fun needsEncryptionMigration(): Boolean {
        if (encryption.isMigrationDone()) return false
        val metaFile = context.getDatabasePath(MetaDatabase.DB_NAME)
        if (!metaFile.exists()) {
            // 没有旧数据库，直接标记完成（新安装）
            encryption.markMigrationDone()
            return false
        }
        return try {
            SQLiteDatabase.openDatabase(
                metaFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).also { it.close() }
            true  // 能用标准 SQLite 打开 = 未加密
        } catch (e: Exception) {
            encryption.markMigrationDone()
            false // 已加密或损坏
        }
    }

    /**
     * 加密迁移流程：
     * 1. 用标准 SQLite 读取未加密数据
     * 2. 删除旧文件（备份保留 .bak）
     * 3. 用 SQLCipher SupportFactory 创建加密数据库
     * 4. 写入数据
     */
    private suspend fun performEncryptionMigration() = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "开始加密迁移...")
        try {
            // ── MetaDatabase（分类）──
            val metaFile = context.getDatabasePath(MetaDatabase.DB_NAME)
            if (metaFile.exists()) {
                val categories = readCategoriesFromPlainDb(metaFile)
                AppLogger.i(TAG, "读取分类: ${categories.size} 条")

                // 备份 + 删除旧文件
                metaFile.copyTo(File(metaFile.parentFile, "${MetaDatabase.DB_NAME}.bak"), overwrite = true)
                // 同时删除 -shm / -wal
                listOf("", "-shm", "-wal").forEach {
                    File(metaFile.parentFile, "${MetaDatabase.DB_NAME}$it").delete()
                }

                // 清除 Room 单例，删除旧文件，重新创建加密数据库
                MetaDatabase.clearInstance()
                listOf("", "-shm", "-wal").forEach {
                    File(metaFile.parentFile, "${MetaDatabase.DB_NAME}$it").delete()
                }
                // 创建新的加密数据库（onCreate 会插入默认分类）
                val newMeta = MetaDatabase.getInstance(context, supportFactory)
                // 先清空默认分类（防止与备份文件中的分类重复），再从备份重新插入
                newMeta.categoryDao().deleteAll()
                if (categories.isNotEmpty()) {
                    // 按 name+type 去重，保留备份中的版本（通常是用户修改后的）
                    val distinct = categories.groupBy { "${it.name}_${it.type}" }
                        .mapValues { it.value.first() }
                        .values
                        .toList()
                    newMeta.categoryDao().insertAll(distinct)
                }
                AppLogger.i(TAG, "加密 MetaDatabase 完成")
            }

            // ── YearDatabase（交易，按年）──
            val years = getAllYears()
            for (year in years) {
                val yearFile = context.getDatabasePath(YearDatabase.dbName(year))
                if (!yearFile.exists()) continue

                val transactions = readTransactionsFromPlainDb(yearFile)
                AppLogger.i(TAG, "读取 $year: ${transactions.size} 条")

                // 备份 + 删除旧文件
                yearFile.copyTo(
                    File(yearFile.parentFile, "${YearDatabase.dbName(year)}.bak"),
                    overwrite = true
                )
                listOf("", "-shm", "-wal").forEach {
                    File(yearFile.parentFile, "${YearDatabase.dbName(year)}$it").delete()
                }

                // 清除缓存，重新用加密工厂创建
                yearDbs.remove(year)?.close()
                val newYearDb = YearDatabase.create(context, year, supportFactory)
                yearDbs[year] = newYearDb
                // 先清空交易表（防止迁移重跑时产生重复）
                newYearDb.transactionDao().deleteAll()
                transactions.forEach { newYearDb.transactionDao().insert(it) }
                AppLogger.i(TAG, "加密 $year 完成")
            }

            encryption.markMigrationDone()
            AppLogger.i(TAG, "加密迁移全部完成")
        } catch (e: Exception) {
            AppLogger.e(TAG, "加密迁移失败", e)
            // 不标记完成，下次启动可重试
        }
    }

    private fun readCategoriesFromPlainDb(file: File): List<Category> {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
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
        return list
    }

    private fun readTransactionsFromPlainDb(file: File): List<Transaction> {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val list = mutableListOf<Transaction>()
        val cursor = db.rawQuery("SELECT * FROM transactions ORDER BY date ASC", null)
        while (cursor.moveToNext()) {
            list.add(
                Transaction(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    type = TransactionType.valueOf(
                        cursor.getString(cursor.getColumnIndexOrThrow("type"))
                    ),
                    amount = cursor.getLong(cursor.getColumnIndexOrThrow("amount")),
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
        return list
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private fun yearFromDate(timeMs: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        return cal.get(Calendar.YEAR)
    }

    fun close() {
        yearDbs.values.forEach { it.close() }
        yearDbs.clear()
    }

    fun invalidateYearDb(year: Int) {
        yearDbs.remove(year)?.close()
    }

    fun invalidateAllYearDbs() {
        yearDbs.values.forEach { it.close() }
        yearDbs.clear()
    }

    /**
     * 重置加密迁移标记并清空所有 DB 缓存
     * 用于从 WebDAV 恢复或 zip 导入未加密数据后，触发重新加密迁移
     * 调用后需重启应用，让 DatabaseManager.initialize() 重新执行迁移
     */
    fun resetForReEncryption() {
        encryption.resetMigrationFlag()
        MetaDatabase.clearInstance()
        invalidateAllYearDbs()
        AppLogger.i(TAG, "已重置加密迁移标记，重启应用后将重新加密")
    }

    sealed class MigrationState {
        object Idle : MigrationState()
        object InProgress : MigrationState()
        object Done : MigrationState()
        data class Error(val message: String) : MigrationState()
    }
}
