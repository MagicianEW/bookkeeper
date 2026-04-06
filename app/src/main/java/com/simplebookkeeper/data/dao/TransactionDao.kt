package com.simplebookkeeper.data.dao

import androidx.room.*
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface TransactionDao {

    // 插入
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: Transaction): Long

    // 更新
    @Update
    suspend fun update(transaction: Transaction)

    // 删除
    @Delete
    suspend fun delete(transaction: Transaction)

    // 按ID删除
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // 按ID查询
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    // 查询某日所有记录（按日期降序）
    @Query("""
        SELECT * FROM transactions 
        WHERE strftime('%Y-%m-%d', date / 1000, 'unixepoch', 'localtime') = :dateStr
        ORDER BY date DESC
    """)
    fun getByDate(dateStr: String): Flow<List<Transaction>>

    // 查询某月所有记录
    @Query("""
        SELECT * FROM transactions 
        WHERE strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
          AND strftime('%m', date / 1000, 'unixepoch', 'localtime') = :month
        ORDER BY date DESC
    """)
    fun getByMonth(year: String, month: String): Flow<List<Transaction>>

    // 查询某年所有记录
    @Query("""
        SELECT * FROM transactions 
        WHERE strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
        ORDER BY date DESC
    """)
    fun getByYear(year: String): Flow<List<Transaction>>

    // 最近N条记录（首页用）
    @Query("SELECT * FROM transactions ORDER BY date DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): Flow<List<Transaction>>

    // 本月收入总额
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM transactions 
        WHERE type = 'INCOME'
          AND strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
          AND strftime('%m', date / 1000, 'unixepoch', 'localtime') = :month
    """)
    fun getMonthlyIncome(year: String, month: String): Flow<Double>

    // 本月支出总额
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM transactions 
        WHERE type = 'EXPENSE'
          AND strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
          AND strftime('%m', date / 1000, 'unixepoch', 'localtime') = :month
    """)
    fun getMonthlyExpense(year: String, month: String): Flow<Double>

    // 某年收入总额
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM transactions 
        WHERE type = 'INCOME'
          AND strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
    """)
    suspend fun getYearlyIncome(year: String): Double

    // 某年支出总额
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM transactions 
        WHERE type = 'EXPENSE'
          AND strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
    """)
    suspend fun getYearlyExpense(year: String): Double

    // 某年储蓄总额
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM transactions 
        WHERE type = 'SAVING'
          AND strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
    """)
    suspend fun getYearlySavingAmount(year: String): Double

    // 某年支取总额
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM transactions 
        WHERE type = 'WITHDRAW'
          AND strftime('%Y', date / 1000, 'unixepoch', 'localtime') = :year
    """)
    suspend fun getYearlyWithdrawAmount(year: String): Double

    // 模糊查询（时间范围 + 金额 + 类型 + 分类 + 备注关键词）
    @Query("""
        SELECT * FROM transactions 
        WHERE (:startDate IS NULL OR date >= :startDate)
          AND (:endDate IS NULL OR date <= :endDate)
          AND (:minAmount IS NULL OR amount >= :minAmount)
          AND (:maxAmount IS NULL OR amount <= :maxAmount)
          AND (:type IS NULL OR type = :type)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:keyword IS NULL OR note LIKE '%' || :keyword || '%')
        ORDER BY date DESC
    """)
    fun search(
        startDate: Long? = null,
        endDate: Long? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        type: String? = null,
        categoryId: Long? = null,
        keyword: String? = null
    ): Flow<List<Transaction>>

    // 所有记录（导出用）
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    suspend fun getAll(): List<Transaction>

    // 获取有记录的所有年份
    @Query("""
        SELECT DISTINCT strftime('%Y', date / 1000, 'unixepoch', 'localtime') as year 
        FROM transactions ORDER BY year DESC
    """)
    fun getAvailableYears(): Flow<List<String>>
}
