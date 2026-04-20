package com.simplebookkeeper.data.repository

import android.content.Context
import com.simplebookkeeper.data.DatabaseManager
import com.simplebookkeeper.data.MetaDatabase
import com.simplebookkeeper.data.dao.CategoryDao
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Calendar

/**
 * 多库版 TransactionRepository
 * 交易操作通过 DatabaseManager 按年路由到对应年库
 * 分类操作绑定到 MetaDatabase
 */
class TransactionRepository(
    private val context: Context,
    metaDb: MetaDatabase // kept for constructor compatibility; categoryDao uses MetaDatabase.getInstance() dynamically
) {

    // 每次调用都从 MetaDatabase 获取当前实例，避免 clearInstance() 后仍持有关闭的旧连接
    private val categoryDao: CategoryDao get() = MetaDatabase.getInstance(context).categoryDao()

    // ——— 账目操作 ———

    suspend fun addTransaction(transaction: Transaction): Long {
        val year = transactionDateToYear(transaction.date.time)
        val dao = DatabaseManager.getTransactionDao(context, year)
        return dao.insert(transaction)
    }

    suspend fun updateTransaction(transaction: Transaction) {
        val year = transactionDateToYear(transaction.date.time)
        val dao = DatabaseManager.getTransactionDao(context, year)
        dao.update(transaction)
    }

    suspend fun deleteTransaction(transaction: Transaction) {
        val year = transactionDateToYear(transaction.date.time)
        val dao = DatabaseManager.getTransactionDao(context, year)
        dao.delete(transaction)
    }

    suspend fun deleteTransactionById(id: Long) {
        // 需要遍历所有年库找到该记录
        for (year in DatabaseManager.getAvailableYears(context)) {
            val dao = DatabaseManager.getTransactionDao(context, year)
            val t = dao.getById(id)
            if (t != null) {
                dao.deleteById(id)
                return
            }
        }
    }

    suspend fun getTransactionById(id: Long): Transaction? {
        // 遍历所有年库查找
        for (year in DatabaseManager.getAvailableYears(context)) {
            val dao = DatabaseManager.getTransactionDao(context, year)
            val t = dao.getById(id)
            if (t != null) return t
        }
        return null
    }

    /**
     * 获取最近N条交易（跨年库合并，按日期降序）
     */
    fun getRecentTransactions(limit: Int = 20): Flow<List<Transaction>> = flow {
        val years = DatabaseManager.getAvailableYears(context)
        if (years.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val allTransactions = mutableListOf<Transaction>()
        for (year in years) {
            val dao = DatabaseManager.getTransactionDao(context, year)
            allTransactions.addAll(dao.getAll())
        }
        emit(allTransactions.sortedByDescending { it.date }.take(limit))
    }

    /**
     * 按月查询交易
     */
    fun getTransactionsByMonth(year: Int, month: Int): Flow<List<Transaction>> = flow {
        val dao = DatabaseManager.getTransactionDao(context, year)
        dao.getByMonth(year.toString(), month.toString().padStart(2, '0'))
            .collect { emit(it) }
    }

    /**
     * 按年查询交易
     */
    fun getTransactionsByYear(year: Int): Flow<List<Transaction>> = flow {
        val dao = DatabaseManager.getTransactionDao(context, year)
        dao.getByYear(year.toString())
            .collect { emit(it) }
    }

    // ——— 统计 ———

    fun getMonthlyIncome(year: Int, month: Int): Flow<Double> = flow {
        val dao = DatabaseManager.getTransactionDao(context, year)
        dao.getMonthlyIncome(year.toString(), month.toString().padStart(2, '0'))
            .collect { emit(it) }
    }

    fun getMonthlyExpense(year: Int, month: Int): Flow<Double> = flow {
        val dao = DatabaseManager.getTransactionDao(context, year)
        dao.getMonthlyExpense(year.toString(), month.toString().padStart(2, '0'))
            .collect { emit(it) }
    }

    suspend fun getYearlyIncome(year: Int): Double {
        val dao = DatabaseManager.getTransactionDao(context, year)
        return dao.getYearlyIncome(year.toString())
    }

    suspend fun getYearlyExpense(year: Int): Double {
        val dao = DatabaseManager.getTransactionDao(context, year)
        return dao.getYearlyExpense(year.toString())
    }

    suspend fun getYearlySavingAmount(year: Int): Double {
        val dao = DatabaseManager.getTransactionDao(context, year)
        return dao.getYearlySavingAmount(year.toString())
    }

    suspend fun getYearlyWithdrawAmount(year: Int): Double {
        val dao = DatabaseManager.getTransactionDao(context, year)
        return dao.getYearlyWithdrawAmount(year.toString())
    }

    /**
     * 获取可用年份列表（从文件系统扫描）
     */
    fun getAvailableYears(): Flow<List<String>> = flow {
        val years = DatabaseManager.getAvailableYears(context).map { it.toString() }
        emit(years)
    }

    // ——— 搜索 ———

    /**
     * 跨年库搜索
     */
    fun search(
        startDate: Long? = null,
        endDate: Long? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        type: TransactionType? = null,
        categoryId: Long? = null,
        keyword: String? = null
    ): Flow<List<Transaction>> = flow {
        // 确定需要搜索的年份范围
        val searchYears = determineSearchYears(startDate, endDate)
        if (searchYears.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val allResults = mutableListOf<Transaction>()
        for (year in searchYears) {
            val dao = DatabaseManager.getTransactionDao(context, year)
            dao.search(
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                type = type?.name,
                categoryId = categoryId,
                keyword = keyword?.ifBlank { null }
            ).collect { allResults.addAll(it) }
        }
        emit(allResults.sortedByDescending { it.date })
    }

    // ——— 导出 ———

    suspend fun getAllTransactions(): List<Transaction> {
        val all = mutableListOf<Transaction>()
        for (year in DatabaseManager.getAvailableYears(context)) {
            val dao = DatabaseManager.getTransactionDao(context, year)
            all.addAll(dao.getAll())
        }
        return all.sortedByDescending { it.date }
    }

    // ——— 分类操作 ———

    fun getAllCategories(): Flow<List<Category>> =
        categoryDao.getAll()

    fun getCategoriesByType(type: TransactionType): Flow<List<Category>> =
        categoryDao.getByType(type)

    suspend fun getCategoryById(id: Long): Category? =
        categoryDao.getById(id)

    suspend fun addCategory(category: Category): Long =
        categoryDao.insert(category)

    suspend fun updateCategory(category: Category) =
        categoryDao.update(category)

    suspend fun deleteCategory(category: Category) =
        categoryDao.delete(category)

    suspend fun getCategoryCount(): Int =
        categoryDao.count()

    // ——— 内部工具 ———

    private fun transactionDateToYear(timestamp: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        return cal.get(Calendar.YEAR)
    }

    /**
     * 根据搜索的日期范围确定需要查询的年份
     */
    private fun determineSearchYears(startDate: Long?, endDate: Long?): List<Int> {
        val availableYears = DatabaseManager.getAvailableYears(context)
        if (availableYears.isEmpty()) return emptyList()

        if (startDate == null && endDate == null) {
            return availableYears
        }

        val startYear = startDate?.let {
            val cal = Calendar.getInstance()
            cal.timeInMillis = it
            cal.get(Calendar.YEAR)
        } ?: availableYears.min()

        val endYear = endDate?.let {
            val cal = Calendar.getInstance()
            cal.timeInMillis = it
            cal.get(Calendar.YEAR)
        } ?: availableYears.max()

        return availableYears.filter { it in startYear..endYear }
    }
}
