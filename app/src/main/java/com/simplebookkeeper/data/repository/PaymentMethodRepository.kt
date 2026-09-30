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

    /**
     * 只取自定义方式。
     *
     * 记账页用它渲染"自定义方式"chip —— 不能用 getByType() 再按名字过滤：
     * 预置方式在库里的名字是中文（现金/微信/…），而界面按当前语言渲染
     * 英文用户就过滤不掉，会同时看到 Cash 和 现金 两个 chip。
     */
    fun getCustomByType(type: TransactionType): Flow<List<PaymentMethodEntity>> =
        dao.getCustomByType(type)

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
