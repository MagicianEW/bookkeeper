package com.simplebookkeeper.data

import android.content.Context
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Calendar

/**
 * 管理按年拆分的交易数据库实例
 * 提供 LRU 缓存，避免同时打开过多数据库连接
 */
object DatabaseManager {

    private const val TAG = "DatabaseManager"
    private const val DB_PREFIX = "bookkeeper_"
    private const val DB_SUFFIX = ".db"
    private const val MAX_CACHE_SIZE = 3 // 最多同时缓存的年库数量

    private val mutex = Mutex()
    private val cache = linkedMapOf<Int, YearDatabase>() // LinkedHashMap 保持插入顺序，支持 LRU

    /**
     * 获取指定年份的数据库实例
     * 如果缓存未命中则创建新实例，缓存满时淘汰最旧的
     */
    suspend fun getYearDb(context: Context, year: Int): YearDatabase = mutex.withLock {
        cache[year]?.let { return@withLock it }

        // LRU 淘汰：移除最旧的条目
        while (cache.size >= MAX_CACHE_SIZE) {
            val oldest = cache.keys.first()
            val db = cache.remove(oldest)
            db?.close()
            AppLogger.d(TAG, "LRU 淘汰年库: $oldest")
        }

        val db = YearDatabase.create(context, year)
        cache[year] = db
        AppLogger.d(TAG, "创建/缓存年库: $year")
        db
    }

    /**
     * 获取指定年份的 TransactionDao
     */
    suspend fun getTransactionDao(context: Context, year: Int): TransactionDao {
        return getYearDb(context, year).transactionDao()
    }

    /**
     * 获取当前年份，如果对应数据库不存在则自动创建
     */
    suspend fun ensureCurrentYearDb(context: Context): YearDatabase {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return getYearDb(context, year)
    }

    /**
     * 获取本地所有已存在的年份列表（通过扫描数据库文件）
     */
    fun getAvailableYears(context: Context): List<Int> {
        val dbDir = context.getDatabasePath("dummy").parentFile ?: return emptyList()
        return dbDir.listFiles()
            ?.filter { it.name.startsWith(DB_PREFIX) && it.name.endsWith(DB_SUFFIX) }
            ?.mapNotNull {
                val yearStr = it.name.removePrefix(DB_PREFIX).removeSuffix(DB_SUFFIX)
                yearStr.toIntOrNull()
            }
            ?.sortedDescending()
            ?: emptyList()
    }

    /**
     * 获取本地所有年份数据库文件
     */
    fun getAllYearDbFiles(context: Context): List<File> {
        val dbDir = context.getDatabasePath("dummy").parentFile ?: return emptyList()
        return dbDir.listFiles()
            ?.filter { it.name.startsWith(DB_PREFIX) && it.name.endsWith(DB_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 获取指定年份的数据库文件
     */
    fun getYearDbFile(context: Context, year: Int): File {
        return context.getDatabasePath(YearDatabase.dbName(year))
    }

    /**
     * 检查指定年份的数据库文件是否存在
     */
    fun yearDbExists(context: Context, year: Int): Boolean {
        return getYearDbFile(context, year).exists()
    }

    /**
     * 关闭所有缓存的数据库连接
     */
    suspend fun closeAll() = mutex.withLock {
        cache.values.forEach { it.close() }
        cache.clear()
        AppLogger.d(TAG, "关闭所有年库连接")
    }

    /**
     * 关闭指定年份的数据库连接
     */
    suspend fun closeYear(year: Int) = mutex.withLock {
        cache.remove(year)?.close()
    }

    /**
     * 获取 meta 数据库文件
     */
    fun getMetaDbFile(context: Context): File {
        return context.getDatabasePath(MetaDatabase.DB_NAME)
    }

    /**
     * 检查旧版单库文件是否存在
     */
    fun legacyDbExists(context: Context): Boolean {
        val file = context.getDatabasePath(AppDatabase.DB_NAME)
        return file.exists() && file.length() > 0
    }

    /**
     * 检查旧版备份文件是否存在
     */
    fun legacyBackupExists(context: Context): Boolean {
        val file = File(context.getDatabasePath(AppDatabase.DB_NAME).absolutePath + ".bak")
        return file.exists()
    }
}
