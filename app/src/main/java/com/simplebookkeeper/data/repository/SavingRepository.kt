package com.simplebookkeeper.data.repository

import com.simplebookkeeper.data.dao.SavingDao
import com.simplebookkeeper.data.model.Saving
import kotlinx.coroutines.flow.Flow

/**
 * 储蓄仓库 — 封装 SavingDao 操作
 */
class SavingRepository(
    private val savingDao: SavingDao
) {
    suspend fun addSaving(saving: Saving): Long = savingDao.insert(saving)

    suspend fun updateSaving(saving: Saving) = savingDao.update(saving)

    suspend fun deleteSaving(saving: Saving) = savingDao.delete(saving)

    suspend fun deleteSavingById(id: Long) = savingDao.deleteById(id)

    fun getAllSavings(): Flow<List<Saving>> = savingDao.getAll()

    suspend fun getAllSavingsSync(): List<Saving> = savingDao.getAllSync()

    suspend fun getSavingById(id: Long): Saving? = savingDao.getById(id)

    fun getBalance(): Flow<Long> = savingDao.getBalance()

    fun getSavingsByMonth(year: String, month: String): Flow<List<Saving>> =
        savingDao.getByMonth(year, month)

    fun getMonthlyDeposit(year: String, month: String): Flow<Long> =
        savingDao.getMonthlyDeposit(year, month)

    fun getMonthlyWithdraw(year: String, month: String): Flow<Long> =
        savingDao.getMonthlyWithdraw(year, month)
}
