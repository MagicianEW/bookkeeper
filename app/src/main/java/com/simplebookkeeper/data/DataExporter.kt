package com.simplebookkeeper.data

import android.content.Context
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
}
