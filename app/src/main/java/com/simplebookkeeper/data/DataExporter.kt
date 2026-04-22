package com.simplebookkeeper.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据导入导出工具
 *
 * 导出：打包所有 db 文件为 bookkeeper_export.zip（含 meta.db + 各年库）
 * 导入：检测 .db 走迁移逻辑，检测 .zip 解压后逐个校验写入
 */
object DataExporter {

    private const val TAG = "DataExporter"

    // ─── 导出 ─────────────────────────────────────────────────

    /**
     * 导出：将所有数据库文件打包为 zip
     * 先关闭所有 DB 连接（包括 MetaDatabase 的 WAL），再打包
     */
    suspend fun exportToZip(context: Context, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // 关闭所有数据库连接（WAL checkpoint，确保数据落盘）
            DatabaseManager.closeAll()
            MetaDatabase.clearInstance()

            // 等待一下让文件锁释放
            kotlinx.coroutines.delay(100)

            val zipOut = ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile)))

            // 添加 meta.db
            val metaFile = DatabaseManager.getMetaDbFile(context)
            AppLogger.i(TAG, "meta.db 路径: ${metaFile.absolutePath}, exists=${metaFile.exists()}")
            if (metaFile.exists()) {
                addToZip(zipOut, "meta.db", metaFile)
                AppLogger.i(TAG, "导出 meta.db, size=${metaFile.length()}")
            } else {
                AppLogger.w(TAG, "meta.db 不存在，跳过")
            }

            // 添加所有年库
            val yearFiles = DatabaseManager.getAllYearDbFilesStatic(context)
            AppLogger.i(TAG, "找到 ${yearFiles.size} 个年库: ${yearFiles.map { it.name }}")
            yearFiles.forEach { file ->
                AppLogger.i(TAG, "导出文件: ${file.absolutePath}, exists=${file.exists()}, size=${file.length()}")
                val entryName = file.name.removePrefix("bookkeeper_")
                addToZip(zipOut, entryName, file)
                AppLogger.i(TAG, "导出 ${file.name} 成功")
            }

            zipOut.close()
            AppLogger.i(TAG, "导出完成: ${outputFile.length()} bytes")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "导出失败", e)
            false
        }
    }

    // ─── 导入 ─────────────────────────────────────────────────

    /**
     * 导入 zip 文件（新版格式）
     */
    suspend fun importFromZip(context: Context, zipFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            DatabaseManager.closeAll()

            val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFile)))
            var entry = zipIn.nextEntry
            var importedCount = 0

            while (entry != null) {
                when (entry.name) {
                    "meta.db" -> {
                        MetaDatabase.clearInstance()
                        val dest = DatabaseManager.getMetaDbFile(context)
                        extractFromZip(zipIn, dest)
                        AppLogger.i(TAG, "导入 meta.db")
                        importedCount++
                    }
                    else -> {
                        if (entry.name.matches(Regex("\\d{4}\\.db"))) {
                            val year = entry.name.removeSuffix(".db").toIntOrNull()
                            if (year != null) {
                                val dest = DatabaseManager.getYearDbFileStatic(context, year)
                                extractFromZip(zipIn, dest)
                                AppLogger.i(TAG, "导入年库: $year")
                                importedCount++
                            }
                        }
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            zipIn.close()

            // 校验
            val metaFile = DatabaseManager.getMetaDbFile(context)
            if (metaFile.exists() && !isValidSqlite(metaFile)) {
                AppLogger.e(TAG, "meta.db 校验失败")
                return@withContext false
            }

            // 清除所有 DB 缓存（Room 会自动执行 MIGRATION_1_2）
            DatabaseManager.closeAll()
            MetaDatabase.clearInstance()
            AppLogger.i(TAG, "已清除 DB 缓存")

            AppLogger.i(TAG, "导入完成: $importedCount 个文件")
            importedCount > 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "导入 zip 失败", e)
            false
        }
    }

    /**
     * 导入旧格式 .db 文件（首次升级迁移）
     */
    suspend fun importLegacyDb(context: Context, dbFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isValidSqlite(dbFile)) {
                AppLogger.e(TAG, "不是有效的 SQLite 文件")
                return@withContext false
            }

            DatabaseManager.closeAll()

            // 复制到旧库路径，由 DatabaseManager 的迁移逻辑处理
            val legacyFile = context.getDatabasePath(AppDatabase.DB_NAME)
            dbFile.copyTo(legacyFile, overwrite = true)

            // 触发迁移
            val dbManager = DatabaseManager(context)
            dbManager.initialize()
            dbManager.close()

            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "旧格式导入失败", e)
            false
        }
    }

    // ─── 工具方法 ───────────────────────────────────────────────

    fun isZipFile(file: File): Boolean {
        if (!file.exists()) return false
        val header = ByteArray(4)
        FileInputStream(file).use { it.read(header) }
        return header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
    }

    fun isValidSqlite(file: File): Boolean {
        if (!file.exists()) return false
        val header = ByteArray(16)
        FileInputStream(file).use { it.read(header) }
        return "SQLite format 3\u0000".toByteArray().contentEquals(header)
    }

    private fun addToZip(zipOut: ZipOutputStream, entryName: String, file: File) {
        val entry = ZipEntry(entryName)
        zipOut.putNextEntry(entry)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var len: Int
            while (input.read(buffer).also { len = it } > 0) {
                zipOut.write(buffer, 0, len)
            }
        }
        zipOut.closeEntry()
    }

    private fun extractFromZip(zipIn: ZipInputStream, destFile: File) {
        // 清理 WAL/SHM
        destFile.delete()
        File("${destFile.absolutePath}-wal").delete()
        File("${destFile.absolutePath}-shm").delete()

        BufferedOutputStream(FileOutputStream(destFile)).use { out ->
            val buffer = ByteArray(8192)
            var len: Int
            while (zipIn.read(buffer).also { len = it } > 0) {
                out.write(buffer, 0, len)
            }
        }
    }

    /**
     * 检查年库是否需要 v1→v2 迁移（amount: Double→Long），如需要则手动执行
     *
     * Room 的自动迁移依赖 identity_hash 校验，导入旧版 .db 时可能失败导致崩溃。
     * 这里用原始 SQL 直接操作，更可靠。
     */
    internal fun migrateYearDbIfNeeded(dbFile: File) {
        if (!dbFile.exists()) return
        try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE
            )

            // 检查 room_master_table 中的版本号
            val version = try {
                val cursor = db.rawQuery(
                    "SELECT version FROM room_master_table WHERE id = 42", null
                )
                val v = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                cursor.close()
                v
            } catch (e: Exception) {
                // 没有 room_master_table，说明不是 Room 创建的，或版本极旧
                AppLogger.w(TAG, "${dbFile.name}: 无 room_master_table，检查原始 schema")
                0
            }

            AppLogger.i(TAG, "${dbFile.name}: schema version = $version")

            if (version < 2) {
                // 检查 amount 列当前类型
                val amountType = try {
                    val cursor = db.rawQuery(
                        "SELECT type FROM pragma_table_info('transactions') WHERE name = 'amount'",
                        null
                    )
                    val t = if (cursor.moveToFirst()) cursor.getString(0) else ""
                    cursor.close()
                    t.uppercase()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "${dbFile.name}: 无法读取 amount 类型", e)
                    db.close()
                    return
                }

                AppLogger.i(TAG, "${dbFile.name}: amount 类型 = $amountType")

                if (amountType == "REAL" || amountType == "FLOAT" || amountType == "DOUBLE") {
                    // 执行迁移：Double(元) → Long(分)
                    AppLogger.i(TAG, "${dbFile.name}: 执行 v1→v2 迁移 (Double→Long)")

                    db.execSQL("""
                        CREATE TABLE transactions_v2(
                            id INTEGER NOT NULL PRIMARY KEY,
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

                    // 更新 Room 版本标记
                    try {
                        db.execSQL("DELETE FROM room_master_table WHERE id = 42")
                        db.execSQL(
                            "INSERT INTO room_master_table(id, hash, version) VALUES(42, ?, 2)",
                            arrayOf("manual_migration_v2")
                        )
                    } catch (e: Exception) {
                        // 可能没有 room_master_table，创建一个
                        try {
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS room_master_table(id INTEGER, hash TEXT, version INTEGER)"
                            )
                            db.execSQL(
                                "INSERT INTO room_master_table(id, hash, version) VALUES(42, ?, 2)",
                                arrayOf("manual_migration_v2")
                            )
                        } catch (e2: Exception) {
                            AppLogger.w(TAG, "${dbFile.name}: 无法更新 room_master_table", e2)
                        }
                    }

                    AppLogger.i(TAG, "${dbFile.name}: 迁移完成")
                } else {
                    AppLogger.i(TAG, "${dbFile.name}: amount 已经是 INTEGER，无需迁移")
                    // 只更新版本号
                    try {
                        db.execSQL("UPDATE room_master_table SET version = 2 WHERE id = 42")
                    } catch (_: Exception) {}
                }
            }

            db.close()
        } catch (e: Exception) {
            AppLogger.e(TAG, "${dbFile.name}: 迁移检查失败", e)
        }
    }
}
