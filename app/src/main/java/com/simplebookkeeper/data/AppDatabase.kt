package com.simplebookkeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.simplebookkeeper.data.dao.CategoryDao
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Converters
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Transaction::class, Category::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        const val DB_NAME = "bookkeeper.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // 插入默认分类
                            CoroutineScope(Dispatchers.IO).launch {
                                INSTANCE?.categoryDao()?.insertAll(defaultCategories())
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // 默认分类数据
        fun defaultCategories(): List<Category> = listOf(
            // 支出分类
            Category(name = "餐饮", type = TransactionType.EXPENSE, icon = "restaurant", isDefault = true, sortOrder = 1),
            Category(name = "交通", type = TransactionType.EXPENSE, icon = "directions_car", isDefault = true, sortOrder = 2),
            Category(name = "购物", type = TransactionType.EXPENSE, icon = "shopping_bag", isDefault = true, sortOrder = 3),
            Category(name = "住房", type = TransactionType.EXPENSE, icon = "home", isDefault = true, sortOrder = 4),
            Category(name = "娱乐", type = TransactionType.EXPENSE, icon = "sports_esports", isDefault = true, sortOrder = 5),
            Category(name = "医疗", type = TransactionType.EXPENSE, icon = "local_hospital", isDefault = true, sortOrder = 6),
            Category(name = "教育", type = TransactionType.EXPENSE, icon = "school", isDefault = true, sortOrder = 7),
            Category(name = "通讯", type = TransactionType.EXPENSE, icon = "phone", isDefault = true, sortOrder = 8),
            Category(name = "其他支出", type = TransactionType.EXPENSE, icon = "more_horiz", isDefault = true, sortOrder = 99),
            // 收入分类
            Category(name = "工资", type = TransactionType.INCOME, icon = "work", isDefault = true, sortOrder = 1),
            Category(name = "理财", type = TransactionType.INCOME, icon = "trending_up", isDefault = true, sortOrder = 2),
            Category(name = "兼职", type = TransactionType.INCOME, icon = "business_center", isDefault = true, sortOrder = 3),
            Category(name = "礼金", type = TransactionType.INCOME, icon = "card_giftcard", isDefault = true, sortOrder = 4),
            Category(name = "其他收入", type = TransactionType.INCOME, icon = "more_horiz", isDefault = true, sortOrder = 99),
        )
    }
}
