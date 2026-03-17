package com.simplebookkeeper.data.repository

import com.simplebookkeeper.data.dao.CategoryDao
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class TransactionRepository(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao
) {

    // ——— 账目操作 ———

    suspend fun addTransaction(transaction: Transaction): Long =
        transactionDao.insert(transaction)

    suspend fun updateTransaction(transaction: Transaction) =
        transactionDao.update(transaction)

    suspend fun deleteTransaction(transaction: Transaction) =
        transactionDao.delete(transaction)

    suspend fun getTransactionById(id: Long): Transaction? =
        transactionDao.getById(id)

    fun getRecentTransactions(limit: Int = 20): Flow<List<Transaction>> =
        transactionDao.getRecent(limit)

    fun getTransactionsByMonth(year: Int, month: Int): Flow<List<Transaction>> =
        transactionDao.getByMonth(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    fun getTransactionsByYear(year: Int): Flow<List<Transaction>> =
        transactionDao.getByYear(year.toString())

    // ——— 统计 ———

    fun getMonthlyIncome(year: Int, month: Int): Flow<Double> =
        transactionDao.getMonthlyIncome(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    fun getMonthlyExpense(year: Int, month: Int): Flow<Double> =
        transactionDao.getMonthlyExpense(
            year.toString(),
            month.toString().padStart(2, '0')
        )

    suspend fun getYearlyIncome(year: Int): Double =
        transactionDao.getYearlyIncome(year.toString())

    suspend fun getYearlyExpense(year: Int): Double =
        transactionDao.getYearlyExpense(year.toString())

    fun getAvailableYears(): Flow<List<String>> =
        transactionDao.getAvailableYears()

    // ——— 搜索 ———

    fun search(
        startDate: Long? = null,
        endDate: Long? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        type: TransactionType? = null,
        categoryId: Long? = null,
        keyword: String? = null
    ): Flow<List<Transaction>> = transactionDao.search(
        startDate = startDate,
        endDate = endDate,
        minAmount = minAmount,
        maxAmount = maxAmount,
        type = type?.name,
        categoryId = categoryId,
        keyword = keyword?.ifBlank { null }
    )

    // ——— 导出 ———

    suspend fun getAllTransactions(): List<Transaction> =
        transactionDao.getAll()

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
}
