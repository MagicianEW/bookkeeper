package com.simplebookkeeper.data.repository

import com.simplebookkeeper.data.DatabaseManager
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.Calendar

/**
 * 交易仓库 — 按年拆分数据库架构
 * 所有交易操作通过 DatabaseManager 路由到对应年份的数据库
 */
class TransactionRepository(
    private val dbManager: DatabaseManager
) {
    // ─── 账目操作 ──────────────────────────────────────────────

    suspend fun addTransaction(transaction: Transaction): Long =
        dbManager.insertTransaction(transaction)

    suspend fun updateTransaction(transaction: Transaction) =
        dbManager.updateTransaction(transaction)

    suspend fun deleteTransaction(transaction: Transaction) =
        dbManager.deleteTransaction(transaction)

    suspend fun deleteTransactionById(id: Long, year: Int) =
        dbManager.deleteTransactionById(id, year)

    suspend fun getTransactionById(id: Long): Transaction? =
        dbManager.getTransactionById(id)

    // ─── 查询 Flow ─────────────────────────────────────────────

    /** 获取最近记录（当前年份） */
    fun getRecentTransactions(limit: Int = 20): Flow<List<Transaction>> =
        dbManager.getCurrentYearDao().getRecent(limit)

    /** 获取指定月份记录 */
    fun getTransactionsByMonth(year: Int, month: Int): Flow<List<Transaction>> =
        dbManager.getYearDao(year).getByMonth(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    /** 获取指定年份记录 */
    fun getTransactionsByYear(year: Int): Flow<List<Transaction>> =
        dbManager.getYearDao(year).getByYear(year.toString())

    // ─── 统计 ──────────────────────────────────────────────────

    fun getMonthlyIncome(year: Int, month: Int): Flow<Long> =
        dbManager.getYearDao(year).getMonthlyIncome(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    fun getMonthlyExpense(year: Int, month: Int): Flow<Long> =
        dbManager.getYearDao(year).getMonthlyExpense(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    suspend fun getYearlyIncome(year: Int): Long =
        dbManager.getYearDao(year).getYearlyIncome(year.toString())

    suspend fun getYearlyExpense(year: Int): Long =
        dbManager.getYearDao(year).getYearlyExpense(year.toString())

    suspend fun getYearlySavingAmount(year: Int): Long =
        dbManager.getYearDao(year).getYearlySavingAmount(year.toString())

    suspend fun getYearlyWithdrawAmount(year: Int): Long =
        dbManager.getYearDao(year).getYearlyWithdrawAmount(year.toString())

    /** 获取所有有数据的年份（跨所有数据库） */
    fun getAvailableYears(): Flow<List<String>> =
        flowOf(dbManager.getAllYears().map { it.toString() })

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
        // 搜索范围：当前年份（性能优先）
        return dbManager.getCurrentYearDao().search(
            startDate, endDate, minAmount, maxAmount,
            type?.name, categoryId, keyword
        )
    }

    // ─── 导出（全量数据） ──────────────────────────────────────

    /** 获取所有年份所有记录 */
    suspend fun getAllTransactions(): List<Transaction> {
        return dbManager.getAllYears().flatMap { year ->
            dbManager.getYearDao(year).getAll()
        }
    }

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

    // ─── 辅助 ─────────────────────────────────────────────────

    /** 从 Transaction 提取年份 */
    fun yearOf(transaction: Transaction): Int {
        val cal = Calendar.getInstance()
        cal.time = transaction.date
        return cal.get(Calendar.YEAR)
    }
}
