package com.simplebookkeeper.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Saving
import com.simplebookkeeper.data.model.SavingType
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
import java.util.Date

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

data class SavingsUiState(
    val balance: Long = 0L,
    val savings: List<Saving> = emptyList(),
    val monthlyDeposit: Long = 0L,
    val monthlyWithdraw: Long = 0L
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BookkeeperApp
    private val repo: TransactionRepository = app.transactionRepository
    private val savingRepo: SavingRepository = app.savingRepository

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
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categoriesMap: StateFlow<Map<Long, Category>> = allCategories
        .map { list -> list.associateBy { it.id } }
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

    // ─── 储蓄相关 ──────────────────────────────────────────────

    val savingsBalance: StateFlow<Long> = savingRepo.getBalance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val allSavings: StateFlow<List<Saving>> = savingRepo.getAllSavings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    fun deleteTransactionById(id: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteTransactionById(id)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
            onDone()
        }
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

    // ─── 储蓄操作 ──────────────────────────────────────────────

    fun addSaving(type: SavingType, amountYuan: Double, note: String, date: Date = Date()) {
        viewModelScope.launch {
            val amountFen = (amountYuan * 100).toLong()
            val saving = Saving(
                type = type,
                amount = amountFen,
                note = note,
                date = date,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            savingRepo.addSaving(saving)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
        }
    }

    fun deleteSaving(saving: Saving) {
        viewModelScope.launch {
            savingRepo.deleteSaving(saving)
        }
    }

    fun deleteSavingById(id: Long) {
        viewModelScope.launch {
            savingRepo.deleteSavingById(id)
        }
    }

    // ─── 同步 ──────────────────────────────────────────────────

    /** 手动同步：导出 ZIP → 上传到 WebDAV
     *  password 参数保留用于外部（如恢复场景）传入临时密码；
     *  若不传，则自动从 PasswordManager 的安全存储取出明文密码。
     */
    fun syncNow(config: WebDavConfig, password: String? = null, onResult: (SyncResult) -> Unit) {
        viewModelScope.launch {
            try {
                val tempFile = java.io.File(app.cacheDir, "sync_export.zip")
                // 优先使用传入的密码，否则从安全存储取明文密码
                val exportPassword = if (app.passwordManager.isPasswordEnabled.first()) {
                    password ?: app.passwordManager.getPlainPassword()
                } else null
                val success = DataExporter.exportToZip(app, tempFile, exportPassword)
                if (success) {
                    val zipBytes = tempFile.readBytes()
                    tempFile.delete()
                    val uploadSuccess = app.webDavManager.uploadData(zipBytes, config)
                    if (uploadSuccess) {
                        onResult(SyncResult.Success)
                    } else {
                        onResult(SyncResult.Error("上传失败"))
                    }
                } else {
                    tempFile.delete()
                    onResult(SyncResult.Error("导出失败"))
                }
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "同步异常", e)
                onResult(SyncResult.Error(e.message ?: "同步异常"))
            }
        }
    }

    /** 下载云端数据：从 WebDAV 下载 ZIP → 导入 */
    fun downloadFromCloud(config: WebDavConfig, password: String? = null, onResult: (SyncResult) -> Unit) {
        viewModelScope.launch {
            try {
                val zipBytes = app.webDavManager.downloadData(config)
                if (zipBytes == null) {
                    onResult(SyncResult.Error("REMOTE_NOT_FOUND"))
                    return@launch
                }
                val tempFile = java.io.File(app.cacheDir, "sync_import.zip")
                tempFile.writeBytes(zipBytes)
                val success = DataExporter.importFromZip(app, tempFile, password)
                tempFile.delete()
                if (success) {
                    onResult(SyncResult.Success)
                } else {
                    onResult(SyncResult.Error("导入失败"))
                }
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "下载异常", e)
                onResult(SyncResult.Error(e.message ?: "下载异常"))
            }
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

    fun getTransactionsByYearSnapshot(year: Int): kotlinx.coroutines.flow.Flow<List<Transaction>> =
        repo.getTransactionsByYear(year)
}
