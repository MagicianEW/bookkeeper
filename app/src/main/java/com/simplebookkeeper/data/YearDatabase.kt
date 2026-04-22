package com.simplebookkeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Converters
import com.simplebookkeeper.data.model.Transaction

/**
 * 按年拆分的交易数据库
 * 每个年份对应一个独立的 db 文件，如 bookkeeper_2025.db、bookkeeper_2026.db
 *
 * @version 2 — amount: Double → Long（单位：分）
 */
@Database(
    entities = [Transaction::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class YearDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    companion object {
        const val VERSION = 2

        /** 获取指定年份的数据库名 */
        fun dbName(year: Int): String = "bookkeeper_$year.db"

        /**
         * 获取指定年份的数据库实例
         * 缓存在 DatabaseManager 中统一管理，这里只提供创建方法
         */
        fun create(context: Context, year: Int): YearDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                YearDatabase::class.java,
                dbName(year)
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
        }

        /** v1→v2：amount 从 Double(元) 迁移到 Long(分)，旧数据 *100 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite 不支持直接改列类型，用临时表过渡
                db.execSQL("""
                    CREATE TABLE transactions_v2(
                        id INTEGER PRIMARY KEY,
                        type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        categoryId INTEGER NOT NULL,
                        paymentMethod TEXT NOT NULL,
                        note TEXT NOT NULL,
                        date INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                // 迁移数据：旧 amount(元) × 100 → 新 amount(分)
                db.execSQL("""
                    INSERT INTO transactions_v2(id,type,amount,categoryId,paymentMethod,note,date,createdAt,updatedAt)
                    SELECT
                        id, type,
                        CAST(amount * 100 AS INTEGER),
                        categoryId, paymentMethod, note, date, createdAt, updatedAt
                    FROM transactions
                """.trimIndent())
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_v2 RENAME TO transactions")
            }
        }
    }
}
