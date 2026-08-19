package com.simplebookkeeper.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 支付/收款方式实体
 * 支持用户自定义方式名称，与 Transaction.paymentMethod (枚举) 共存
 * - Transaction.paymentMethod 存标准方式（微信/支付宝/现金/银行卡）
 * - Transaction.customPaymentMethod 存用户自定义方式名称（二者二选一）
 */
@Entity(tableName = "payment_methods")
data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,              // 方式名称，如"企业转账"
    val type: TransactionType,     // PAYMENT 或 COLLECTION
    val isDefault: Boolean = false // 是否为预置方式（不可删除）
)
