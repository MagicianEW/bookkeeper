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
}
