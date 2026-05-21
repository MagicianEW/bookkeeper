package com.simplebookkeeper.data.dao

import androidx.room.*
import com.simplebookkeeper.data.model.Saving
import kotlinx.coroutines.flow.Flow

@Dao
interface SavingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(saving: Saving): Long

    @Update
    suspend fun update(saving: Saving)

    @Delete
    suspend fun delete(saving: Saving)

    @Query("DELETE FROM savings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM savings ORDER BY date DESC")
    fun getAll(): Flow<List<Saving>>

    @Query("SELECT * FROM savings ORDER BY date DESC")
    suspend fun getAllSync(): List<Saving>

    @Query("SELECT * FROM savings WHERE id = :id")
    suspend fun getById(id: Long): Saving?

    /** 余额：存入总额 - 取出总额 */
    @Query("SELECT COALESCE(SUM(CASE WHEN type='DEPOSIT' THEN amount ELSE -amount END), 0) FROM savings")
    fun getBalance(): Flow<Long>

    /** 按月查询 */
    @Query("""
        SELECT * FROM savings 
        WHERE strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
          AND strftime('%m', date / 1000, 'unixepoch', 'localtime') = :month
        ORDER BY date DESC
    """)
    fun getByMonth(year: String, month: String): Flow<List<Saving>>

    /** 某月存入总额 */
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM savings
        WHERE type = 'DEPOSIT'
          AND strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
          AND strftime('%m', date / 1000, 'unixepoch', 'localtime') = :month
    """)
    fun getMonthlyDeposit(year: String, month: String): Flow<Long>

    /** 某月取出总额 */
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM savings
        WHERE type = 'WITHDRAW'
          AND strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
          AND strftime('%m', date / 1000, 'unixepoch', 'localtime') = :month
    """)
    fun getMonthlyWithdraw(year: String, month: String): Flow<Long>

    @Query("DELETE FROM savings")
    suspend fun deleteAll()
}
