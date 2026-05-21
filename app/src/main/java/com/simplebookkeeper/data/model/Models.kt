package com.simplebookkeeper.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.Date

// 收支类型
enum class TransactionType { INCOME, EXPENSE }

// 付款方式
enum class PaymentMethod {
    CASH, WECHAT, ALIPAY, BANK_CARD, CREDIT_CARD, OTHER
}

// 账目记录
@Entity(tableName = "transactions")
@TypeConverters(Converters::class)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: TransactionType,
    val amount: Long,             // 金额（单位：分），正数
    val categoryId: Long,           // 分类ID
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val note: String = "",          // 备注
    val date: Date = Date(),        // 日期
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// 分类
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: TransactionType,      // 收入分类 or 支出分类
    val icon: String = "",          // 图标名称（Material Icons）
    val isDefault: Boolean = false, // 是否为内置分类
    val sortOrder: Int = 0
)

// 类型转换器
class Converters {
    @TypeConverter
    fun fromDate(date: Date?): Long? = date?.time

    @TypeConverter
    fun toDate(time: Long?): Date? = time?.let { Date(it) }

    @TypeConverter
    fun fromTransactionType(type: TransactionType): String = type.name

    @TypeConverter
    fun toTransactionType(name: String): TransactionType = TransactionType.valueOf(name)

    @TypeConverter
    fun fromPaymentMethod(method: PaymentMethod): String = method.name

    @TypeConverter
    fun toPaymentMethod(name: String): PaymentMethod = PaymentMethod.valueOf(name)

    @TypeConverter
    fun fromSavingType(type: SavingType): String = type.name

    @TypeConverter
    fun toSavingType(name: String): SavingType = SavingType.valueOf(name)
}

// 月度汇总（查询结果）
data class MonthlySummary(
    val year: Int,
    val month: Int,
    val totalIncome: Long,   // 单位：分
    val totalExpense: Long   // 单位：分
) {
    val balance: Long get() = totalIncome - totalExpense
}

// 年度汇总（查询结果）
data class YearlySummary(
    val year: Int,
    val totalIncome: Long,   // 单位：分
    val totalExpense: Long    // 单位：分
) {
    val balance: Long get() = totalIncome - totalExpense
}
