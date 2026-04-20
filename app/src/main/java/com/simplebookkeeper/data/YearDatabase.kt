package com.simplebookkeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.simplebookkeeper.data.dao.TransactionDao
import com.simplebookkeeper.data.model.Converters
import com.simplebookkeeper.data.model.Transaction

/**
 * 按年拆分的交易数据库
 * 每个年份对应一个独立的 db 文件，如 bookkeeper_2025.db、bookkeeper_2026.db
 */
@Database(
    entities = [Transaction::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class YearDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    companion object {
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
            ).build()
        }
    }
}
