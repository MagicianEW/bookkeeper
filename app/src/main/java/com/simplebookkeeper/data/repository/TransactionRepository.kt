package com.simplebookkeeper.data.repository

import com.simplebookkeeper.data.DatabaseManager
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 交易仓库 — 单一数据库架构
 * 所有操作直接通过 DatabaseManager 访问单一 AppDatabase
 */
class TransactionRepository(
    private val dbManager: DatabaseManager
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    // 缓存可用年份列表，避免重复查询数据库
    private val _availableYearsCache = MutableStateFlow<List<String>>(emptyList())
    val availableYears: StateFlow<List<String>> = _availableYearsCache

    init {
        refreshAvailableYears()
    }

    private fun refreshAvailableYears() {
        scope.launch {
            val years = dbManager.getAllYears().map { it.toString() }
            _availableYearsCache.value = years
        }
    }

    // ─── 账目操作 ──────────────────────────────────────────────

    suspend fun addTransaction(transaction: Transaction): Long {
        val result = dbManager.transactionDao.insert(transaction)
        refreshAvailableYears() // 新增账目后刷新年份缓存
        return result
    }

    suspend fun updateTransaction(transaction: Transaction) =
        dbManager.transactionDao.update(transaction)

    suspend fun deleteTransaction(transaction: Transaction) {
        dbManager.transactionDao.delete(transaction)
        refreshAvailableYears() // 删除账目后刷新年份缓存
    }

    suspend fun deleteTransactionById(id: Long) {
        dbManager.transactionDao.deleteById(id)
        refreshAvailableYears() // 删除账目后刷新年份缓存
    }

    suspend fun getTransactionById(id: Long): Transaction? =
        dbManager.transactionDao.getById(id)

    // ─── 查询 Flow ─────────────────────────────────────────────

    /** 获取最近记录 */
    fun getRecentTransactions(limit: Int = 20): Flow<List<Transaction>> =
        dbManager.transactionDao.getRecent(limit)

    /** 获取指定月份记录 */
    fun getTransactionsByMonth(year: Int, month: Int): Flow<List<Transaction>> =
        dbManager.transactionDao.getByMonth(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    /** 获取指定年份记录 */
    fun getTransactionsByYear(year: Int): Flow<List<Transaction>> =
        dbManager.transactionDao.getByYear(year.toString())

    // ─── 统计 ──────────────────────────────────────────────────

    fun getMonthlyIncome(year: Int, month: Int): Flow<Long> =
        dbManager.transactionDao.getMonthlyIncome(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    fun getMonthlyExpense(year: Int, month: Int): Flow<Long> =
        dbManager.transactionDao.getMonthlyExpense(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    suspend fun getYearlyIncome(year: Int): Long =
        dbManager.transactionDao.getYearlyIncome(year.toString())

    suspend fun getYearlyExpense(year: Int): Long =
        dbManager.transactionDao.getYearlyExpense(year.toString())

    /** 获取所有有数据的年份（从缓存读取） */
    fun getAvailableYears(): Flow<List<String>> = availableYears

    // ─── 搜索 ─────────────────────────────────────────────────

    fun search(
        startDate: Long? = null,
        endDate: Long? = null,
        minAmount: Long? = null,
        maxAmount: Long? = null,
        type: TransactionType? = null,
        categoryId: Long? = null,
        keyword: String? = null
    ): Flow<List<Transaction>> {
        return dbManager.transactionDao.search(
            startDate, endDate, minAmount, maxAmount,
            type?.name, categoryId, keyword
        )
    }

    // ─── 导出（全量数据） ──────────────────────────────────────

    /** 获取所有记录 */
    suspend fun getAllTransactions(): List<Transaction> =
        dbManager.transactionDao.getAll()

    // ─── 分类操作 ─────────────────────────────────────────────

    fun getAllCategories(): Flow<List<Category>> =
        dbManager.categoryDao.getAll()

    fun getCategoriesByType(type: TransactionType): Flow<List<Category>> =
        dbManager.categoryDao.getByType(type)

    suspend fun getCategoryById(id: Long): Category? =
        dbManager.categoryDao.getById(id)

    suspend fun addCategory(category: Category): Long =
        dbManager.categoryDao.insert(category)

    suspend fun updateCategory(category: Category) =
        dbManager.categoryDao.update(category)

    suspend fun deleteCategory(category: Category) =
        dbManager.categoryDao.delete(category)

    suspend fun getCategoryCount(): Int =
        dbManager.categoryDao.count()
}
