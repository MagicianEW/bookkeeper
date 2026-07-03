package com.simplebookkeeper.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Saving
import com.simplebookkeeper.data.model.SavingType
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.SyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val transactionVM = TransactionViewModel(application)
    private val savingsVM = SavingsViewModel(application)
    private val syncVM = SyncViewModel(application)

    val displayYear: StateFlow<Int> = transactionVM.displayYear
    val displayMonth: StateFlow<Int> = transactionVM.displayMonth
    val monthlyIncome: StateFlow<Long> = transactionVM.monthlyIncome
    val monthlyExpense: StateFlow<Long> = transactionVM.monthlyExpense
    val recentTransactions: StateFlow<List<Transaction>> = transactionVM.recentTransactions
    val allCategories: StateFlow<List<Category>> = transactionVM.allCategories
    val categoriesMap: StateFlow<Map<Long, Category>> = transactionVM.categoriesMap
    val availableYears: StateFlow<List<String>> = transactionVM.availableYears
    val monthlyTransactions: StateFlow<List<Transaction>> = transactionVM.monthlyTransactions
    val searchState = transactionVM.searchState
    val savingsBalance: StateFlow<Long> = savingsVM.savingsBalance
    val allSavings: StateFlow<List<Saving>> = savingsVM.allSavings
    val syncStatus: StateFlow<String?> = syncVM.syncStatus

    fun setDisplayMonth(year: Int, month: Int) = transactionVM.setDisplayMonth(year, month)

    fun addTransaction(transaction: Transaction, onDone: () -> Unit = {}) =
        transactionVM.addTransaction(transaction, onDone)

    fun updateTransaction(transaction: Transaction, onDone: () -> Unit = {}) =
        transactionVM.updateTransaction(transaction, onDone)

    fun deleteTransaction(transaction: Transaction) = transactionVM.deleteTransaction(transaction)

    fun search(
        startDate: Long? = null,
        endDate: Long? = null,
        minAmount: Long? = null,
        maxAmount: Long? = null,
        type: TransactionType? = null,
        categoryId: Long? = null,
        keyword: String? = null
    ) = transactionVM.search(startDate, endDate, minAmount, maxAmount, type, categoryId, keyword)

    suspend fun getYearlySummary(year: Int) = transactionVM.getYearlySummary(year)

    fun deleteTransactionById(id: Long, onDone: () -> Unit = {}) =
        transactionVM.deleteTransactionById(id, onDone)

    fun addCategory(category: Category, onComplete: (() -> Unit)? = null) =
        transactionVM.addCategory(category, onComplete)

    fun updateCategory(category: Category) = transactionVM.updateCategory(category)

    fun deleteCategory(category: Category) = transactionVM.deleteCategory(category)

    fun addSaving(type: SavingType, amountYuan: Double, note: String, date: Date = Date()) =
        savingsVM.addSaving(type, amountYuan, note, date)

    fun deleteSaving(saving: Saving) = savingsVM.deleteSaving(saving)

    fun deleteSavingById(id: Long) = savingsVM.deleteSavingById(id)

    fun syncNow(config: WebDavConfig, password: String? = null, onResult: (SyncResult) -> Unit) =
        syncVM.syncNow(config, password, onResult)

    fun downloadFromCloud(config: WebDavConfig, password: String? = null, onResult: (SyncResult) -> Unit) =
        syncVM.downloadFromCloud(config, password, onResult)

    fun clearSyncStatus() = syncVM.clearSyncStatus()

    fun getTransactionsByYear(year: Int): Flow<List<Transaction>> =
        transactionVM.getTransactionsByYear(year)

    suspend fun getTransactionById(id: Long): Transaction? =
        transactionVM.getTransactionById(id)

    fun getTransactionsByYearSnapshot(year: Int): Flow<List<Transaction>> =
        transactionVM.getTransactionsByYearSnapshot(year)
}
