package com.simplebookkeeper.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.data.repository.SavingRepository
import com.simplebookkeeper.data.repository.TransactionRepository
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.SyncResult
import com.simplebookkeeper.sync.SyncWorker
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

data class TransactionUiState(
    val monthlyIncome: Long = 0L,
    val monthlyExpense: Long = 0L,
    val recentTransactions: List<Transaction> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val availableYears: List<String> = emptyList(),
    val currentYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
)

data class SearchUiState(
    val results: List<Transaction> = emptyList(),
    val isSearching: Boolean = false
)

class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BookkeeperApp
    private val repo: TransactionRepository = app.transactionRepository

    private val _displayYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR))
    private val _displayMonth = MutableStateFlow(Calendar.getInstance().get(Calendar.MONTH) + 1)
    val displayYear: StateFlow<Int> = _displayYear.asStateFlow()
    val displayMonth: StateFlow<Int> = _displayMonth.asStateFlow()

    val monthlyIncome: StateFlow<Long> = combine(_displayYear, _displayMonth) { y, m ->
        Pair(y, m)
    }.flatMapLatest { (y, m) ->
        repo.getMonthlyIncome(y, m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val monthlyExpense: StateFlow<Long> = combine(_displayYear, _displayMonth) { y, m ->
        Pair(y, m)
    }.flatMapLatest { (y, m) ->
        repo.getMonthlyExpense(y, m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val recentTransactions: StateFlow<List<Transaction>> =
        repo.getRecentTransactions(30)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCategories: StateFlow<List<Category>> =
        repo.getAllCategories()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categoriesMap: StateFlow<Map<Long, Category>> = allCategories
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val availableYears: StateFlow<List<String>> =
        repo.getAvailableYears()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val monthlyTransactions: StateFlow<List<Transaction>> =
        combine(_displayYear, _displayMonth) { y, m -> Pair(y, m) }
            .flatMapLatest { (y, m) -> repo.getTransactionsByMonth(y, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    fun setDisplayMonth(year: Int, month: Int) {
        _displayYear.value = year
        _displayMonth.value = month
    }

    fun addTransaction(transaction: Transaction, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.addTransaction(transaction)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
            onDone()
        }
    }

    fun updateTransaction(transaction: Transaction, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.updateTransaction(transaction)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
            onDone()
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            repo.deleteTransaction(transaction)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
        }
    }

    fun search(
        startDate: Long? = null,
        endDate: Long? = null,
        minAmount: Long? = null,
        maxAmount: Long? = null,
        type: TransactionType? = null,
        categoryId: Long? = null,
        keyword: String? = null
    ) {
        viewModelScope.launch {
            _searchState.value = _searchState.value.copy(isSearching = true)
            try {
                repo.search(startDate, endDate, minAmount, maxAmount, type, categoryId, keyword)
                    .collect { results ->
                        _searchState.value = SearchUiState(results = results, isSearching = false)
                    }
            } catch (e: Exception) {
                AppLogger.e("TransactionViewModel", "搜索失败", e)
                _searchState.value = SearchUiState(results = emptyList(), isSearching = false)
            }
        }
    }

    suspend fun getYearlySummary(year: Int): Pair<Long, Long> {
        val income = repo.getYearlyIncome(year)
        val expense = repo.getYearlyExpense(year)
        return Pair(income, expense)
    }

    fun deleteTransactionById(id: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteTransactionById(id)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
            onDone()
        }
    }

    fun addCategory(category: Category, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            repo.addCategory(category)
            onComplete?.invoke()
        }
    }

    fun updateCategory(category: Category) {
        viewModelScope.launch { repo.updateCategory(category) }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch { repo.deleteCategory(category) }
    }

    fun getTransactionsByYear(year: Int): Flow<List<Transaction>> =
        repo.getTransactionsByYear(year)

    suspend fun getTransactionById(id: Long): Transaction? =
        repo.getTransactionById(id)

    fun getTransactionsByYearSnapshot(year: Int): Flow<List<Transaction>> =
        repo.getTransactionsByYear(year)
}
