package com.simplebookkeeper.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.data.model.Saving
import com.simplebookkeeper.data.model.SavingType
import com.simplebookkeeper.data.repository.SavingRepository
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.SyncWorker
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date

class SavingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BookkeeperApp
    private val savingRepo: SavingRepository = app.savingRepository

    val savingsBalance: StateFlow<Long> = savingRepo.getBalance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val allSavings: StateFlow<List<Saving>> = savingRepo.getAllSavings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 金额单位：分。amountFen 必须是整数分，避免浮点误差 */
    fun addSaving(type: SavingType, amountFen: Long, note: String, date: Date = Date()) {
        viewModelScope.launch {
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
            // 删除同样需要同步，否则云端仍保留已删除的记录
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
        }
    }

    fun deleteSavingById(id: Long) {
        viewModelScope.launch {
            savingRepo.deleteSavingById(id)
            val config = app.settingsRepository.webDavConfig.first()
            if (config.enabled) SyncWorker.syncNow(app)
        }
    }
}
