package com.simplebookkeeper.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.util.Date

/** 储蓄类型：存入或取出 */
enum class SavingType { DEPOSIT, WITHDRAW }

/**
 * 储蓄记录
 * 独立于账本数据，记录存取款操作
 */
@Entity(tableName = "savings")
@TypeConverters(Converters::class)
data class Saving(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: SavingType,             // 存入或取出
    val amount: Long,                 // 金额（单位：分），正数
    val note: String = "",            // 备注
    val date: Date = Date(),          // 日期
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
