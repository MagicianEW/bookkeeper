package com.simplebookkeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.simplebookkeeper.data.dao.CategoryDao
import com.simplebookkeeper.data.dao.PaymentMethodDao
import com.simplebookkeeper.data.dao.SavingDao
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Converters
import com.simplebookkeeper.data.model.PaymentMethodEntity
import com.simplebookkeeper.data.model.Saving
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主数据库 — 统一管理交易、分类、储蓄
 *
 * 合并原 MetaDatabase + YearDatabase 为单一 bookkeeper.db
 * version 3: 新增 savings 表
 * version 4: 新增 payment_methods 表 + transactions.customPaymentMethod 字段
 */
@Database(
    entities = [Transaction::class, Category::class, Saving::class, PaymentMethodEntity::class],
    version = 6,
    // 打开 schema 导出：Room 才能在迁移时校验表结构，
    // 后续加版本也能用 MigrationTestHelper 回归测试
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun savingDao(): SavingDao
    abstract fun paymentMethodDao(): PaymentMethodDao

    companion object {
        const val DB_NAME = "bookkeeper.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context, factory: SupportSQLiteOpenHelper.Factory): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .openHelperFactory(factory)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // 数据库首次创建时插入 14 个默认分类
                            defaultCategories().forEach { cat ->
                                db.execSQL(
                                    "INSERT INTO categories(name, type, icon, isDefault, sortOrder) VALUES(?, ?, ?, ?, ?)",
                                    arrayOf(cat.name, cat.type.name, cat.icon, if (cat.isDefault) 1 else 0, cat.sortOrder)
                                )
                            }
                            // 插入默认支付/收款方式
                            defaultPaymentMethods().forEach { pm ->
                                db.execSQL(
                                    "INSERT INTO payment_methods(name, type, isDefault) VALUES(?, ?, ?)",
                                    arrayOf(pm.name, pm.type.name, if (pm.isDefault) 1 else 0)
                                )
                            }
                        }
                    })
                    .addMigrations(object : Migration(3, 4) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            // 新增 payment_methods 表
                            db.execSQL("""
                                CREATE TABLE IF NOT EXISTS payment_methods (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    name TEXT NOT NULL,
                                    type TEXT NOT NULL,
                                    isDefault INTEGER NOT NULL DEFAULT 0
                                )
                            """.trimIndent())
                            // 清理旧版预置方式（避免升级后重复显示）
                            db.execSQL("DELETE FROM payment_methods WHERE isDefault = 1")
                            // 重新插入统一预置方式（现金/微信/支付宝/银行卡）
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('现金', 'EXPENSE', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('微信', 'EXPENSE', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('支付宝', 'EXPENSE', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('银行卡', 'EXPENSE', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('现金', 'INCOME', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('微信', 'INCOME', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('支付宝', 'INCOME', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('银行卡', 'INCOME', 1)")
                            // transactions 表新增自定义方式字段
                            db.execSQL("ALTER TABLE transactions ADD COLUMN customPaymentMethod TEXT")
                        }
                    })
                    .addMigrations(object : Migration(4, 5) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            // 清空并重新插入统一预置方式（修复 v3→v4 时的旧数据残留）
                            db.execSQL("DELETE FROM payment_methods WHERE isDefault = 1")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('现金', 'EXPENSE', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('微信', 'EXPENSE', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('支付宝', 'EXPENSE', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('银行卡', 'EXPENSE', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('现金', 'INCOME', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('微信', 'INCOME', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('支付宝', 'INCOME', 1)")
                            db.execSQL("INSERT INTO payment_methods(name, type, isDefault) VALUES('银行卡', 'INCOME', 1)")
                        }
                    })
                    .addMigrations(object : Migration(5, 6) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            // 将"微信支付"修正为"微信"，与 FilterChip 枚举标签一致
                            db.execSQL("UPDATE payment_methods SET name = '微信' WHERE name = '微信支付'")
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * 清除单例引用（导入数据后需要重新创建数据库实例）
         */
        fun clearInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }

        /**
         * 关闭当前单例并立刻重建一个新的实例。
         *
         * 用于「导入 / 云端恢复」之后：旧实例已被 close()，所有持有它的
         * DAO 都会打到已关闭的库上。必须把新实例交回 DatabaseManager，
         * 否则应用会停留在"导入成功但再也记不上账"的状态。
         */
        fun recreateInstance(context: Context, factory: SupportSQLiteOpenHelper.Factory): AppDatabase {
            clearInstance()
            return getInstance(context, factory)
        }

        /** 默认分类数据（与原 MetaDatabase 保持一致） */
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

        /** 默认支付/收款方式（预置方式不可删除） */
        fun defaultPaymentMethods(): List<PaymentMethodEntity> = listOf(
            // 支出-付款方式
            PaymentMethodEntity(name = "现金", type = TransactionType.EXPENSE, isDefault = true),
            PaymentMethodEntity(name = "微信", type = TransactionType.EXPENSE, isDefault = true),
            PaymentMethodEntity(name = "支付宝", type = TransactionType.EXPENSE, isDefault = true),
            PaymentMethodEntity(name = "银行卡", type = TransactionType.EXPENSE, isDefault = true),
            // 收入-收款方式
            PaymentMethodEntity(name = "现金", type = TransactionType.INCOME, isDefault = true),
            PaymentMethodEntity(name = "微信", type = TransactionType.INCOME, isDefault = true),
            PaymentMethodEntity(name = "支付宝", type = TransactionType.INCOME, isDefault = true),
            PaymentMethodEntity(name = "银行卡", type = TransactionType.INCOME, isDefault = true),
        )
    }
}
