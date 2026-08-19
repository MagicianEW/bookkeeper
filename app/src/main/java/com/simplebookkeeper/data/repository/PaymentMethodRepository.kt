package com.simplebookkeeper.data.repository

import com.simplebookkeeper.data.dao.PaymentMethodDao
import com.simplebookkeeper.data.model.PaymentMethodEntity
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

/**
 * 支付/收款方式仓库
 */
class PaymentMethodRepository(
    private val dao: PaymentMethodDao
) {
    fun getAll(): Flow<List<PaymentMethodEntity>> = dao.getAll()

    fun getByType(type: TransactionType): Flow<List<PaymentMethodEntity>> =
        dao.getByType(type)

    suspend fun getById(id: Long): PaymentMethodEntity? = dao.getById(id)

    suspend fun add(entity: PaymentMethodEntity): Long = dao.insert(entity)

    suspend fun update(entity: PaymentMethodEntity) = dao.update(entity)

    suspend fun delete(entity: PaymentMethodEntity) = dao.delete(entity)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /**
     * 检查同名方式是否已存在
     */
    suspend fun exists(name: String, type: TransactionType): Boolean =
        dao.findByNameAndType(name, type) != null
}
