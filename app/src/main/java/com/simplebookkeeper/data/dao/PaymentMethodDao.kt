package com.simplebookkeeper.data.dao

import androidx.room.*
import com.simplebookkeeper.data.model.PaymentMethodEntity
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentMethodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PaymentMethodEntity): Long

    @Update
    suspend fun update(entity: PaymentMethodEntity)

    @Delete
    suspend fun delete(entity: PaymentMethodEntity)

    @Query("SELECT * FROM payment_methods ORDER BY isDefault DESC, id ASC")
    fun getAll(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_methods WHERE type = :type ORDER BY isDefault DESC, id ASC")
    fun getByType(type: TransactionType): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_methods WHERE id = :id")
    suspend fun getById(id: Long): PaymentMethodEntity?

    @Query("SELECT * FROM payment_methods WHERE type = :type AND name = :name LIMIT 1")
    suspend fun findByNameAndType(name: String, type: TransactionType): PaymentMethodEntity?

    @Query("SELECT COUNT(*) FROM payment_methods")
    suspend fun count(): Int

    @Query("SELECT * FROM payment_methods ORDER BY isDefault DESC, id ASC")
    suspend fun getAllSync(): List<PaymentMethodEntity>

    @Query("DELETE FROM payment_methods WHERE id = :id AND isDefault = 0")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM payment_methods WHERE isDefault = 0")
    suspend fun deleteAllCustom()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PaymentMethodEntity>)
}
