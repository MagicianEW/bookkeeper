package com.simplebookkeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.simplebookkeeper.data.dao.CategoryDao
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Converters
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 元数据库：存储分类等全局共享数据
 * 独立于按年拆分的交易数据库，所有年份共用同一套分类体系
 */
@Database(
    entities = [Category::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MetaDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    companion object {
        const val DB_NAME = "bookkeeper_meta.db"

        @Volatile
        private var INSTANCE: MetaDatabase? = null

        fun getInstance(context: Context): MetaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MetaDatabase::class.java,
                    DB_NAME
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
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

        /**
         * 清除单例引用（导入数据覆盖文件后调用，使下次 getInstance 指向新文件）
         */
        fun clearInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }

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
