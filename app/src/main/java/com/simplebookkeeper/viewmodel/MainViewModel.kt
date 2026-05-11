package com.simplebookkeeper.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.data.repository.TransactionRepository
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.SyncResult
import com.simplebookkeeper.sync.SyncWorker
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

data class HomeUiState(
    val monthlyIncome: Long = 0L,    // 单位：分
    val monthlyExpense: Long = 0L,    // 单位：分
    val recentTransactions: List<Transaction> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val currentYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
)

data class SearchUiState(
    val results: List<Transaction> = emptyList(),
    val isSearching: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BookkeeperApp
    private val repo: TransactionRepository = app.transactionRepository

    // 当前显示月份
    private val _displayYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR))
    private val _displayMonth = MutableStateFlow(Calendar.getInstance().get(Calendar.MONTH) + 1)
    val displayYear: StateFlow<Int> = _displayYear.asStateFlow()
    val displayMonth: StateFlow<Int> = _displayMonth.asStateFlow()

    // 首页数据
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
            .also { flow -> AppLogger.i("MainViewModel", "allCategories flow created") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categoriesMap: StateFlow<Map<Long, Category>> = allCategories
        .map { list -> list.associateBy { it.id }.also { AppLogger.i("MainViewModel", "categoriesMap built with ${it.size} categories") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val availableYears: StateFlow<List<String>> =
        repo.getAvailableYears()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 月度账目列表
    val monthlyTransactions: StateFlow<List<Transaction>> =
        combine(_displayYear, _displayMonth) { y, m -> Pair(y, m) }
            .flatMapLatest { (y, m) -> repo.getTransactionsByMonth(y, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 搜索状态
    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    // 同步状态
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    // 储蓄余额
    val savingsBalance: StateFlow<Long> = app.settingsRepository.savingsBalance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // 数据库迁移状态
    val migrationState = app.dbManager.migrationState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            com.simplebookkeeper.data.DatabaseManager.MigrationState.Idle)

    fun setDisplayMonth(year: Int, month: Int) {
        _displayYear.value = year
        _displayMonth.value = month
    }

    // 新增账目
    fun addTransaction(transaction: Transaction, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.addTransaction(transaction)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
            onDone()
        }
    }

    // 更新账目
    fun updateTransaction(transaction: Transaction, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.updateTransaction(transaction)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
            onDone()
        }
    }

    // 删除账目
    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            repo.deleteTransaction(transaction)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
        }
    }

    // 搜索
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
                AppLogger.e("MainViewModel", "搜索失败", e)
                _searchState.value = SearchUiState(results = emptyList(), isSearching = false)
            }
        }
    }

    // 年度统计
    suspend fun getYearlySummary(year: Int): Pair<Long, Long> {
        val income = repo.getYearlyIncome(year)
        val expense = repo.getYearlyExpense(year)
        return Pair(income, expense)
    }

    // 根据ID删除账目
    fun deleteTransactionById(id: Long, year: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteTransactionById(id, year)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
            onDone()
        }
    }

    // 计算年度储蓄（储蓄总额 - 支取总额）
    suspend fun getYearlySavings(year: Int): Long {
        val savingAmount = repo.getYearlySavingAmount(year)
        val withdrawAmount = repo.getYearlyWithdrawAmount(year)
        return savingAmount - withdrawAmount
    }

    // 分类管理
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

    // 手动同步（多文件）
    fun syncNow(config: WebDavConfig, onResult: (SyncResult) -> Unit) {
        viewModelScope.launch {
            val result = app.webDavManager.syncMulti(config, app.dbManager)
            onResult(result)
        }
    }

    // 下载云端数据库（冲突解决/首次恢复）
    fun downloadFromCloud(config: WebDavConfig, onResult: (SyncResult) -> Unit) {
        viewModelScope.launch {
            val result = app.webDavManager.downloadMulti(config, app.dbManager)
            onResult(result)
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = null
    }

    // 按年查询（供统计页使用）
    fun getTransactionsByYear(year: Int): kotlinx.coroutines.flow.Flow<List<Transaction>> =
        repo.getTransactionsByYear(year)

    // 按ID查询单条（编辑时回填数据）
    suspend fun getTransactionById(id: Long): Transaction? =
        repo.getTransactionById(id)

    // 获取某年份所有记录数（用于删除确认等场景）
    fun getTransactionsByYearSnapshot(year: Int): kotlinx.coroutines.flow.Flow<List<Transaction>> =
        repo.getTransactionsByYear(year)
}
