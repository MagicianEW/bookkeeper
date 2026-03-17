package com.simplebookkeeper.data.dao

import androidx.room.*
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: Category): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>)

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
    fun getAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder, id")
    fun getByType(type: TransactionType): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}
