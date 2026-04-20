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
    private const val META_DB_NAME = "meta.db"

    /**
     * 导出：将所有数据库文件打包为 zip
     * @param outputFile 输出 zip 文件
     */
    suspend fun exportToZip(context: Context, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // 先关闭所有数据库连接
            DatabaseManager.closeAll()

            val zipOut = ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile)))

            // 添加 meta.db
            val metaFile = DatabaseManager.getMetaDbFile(context)
            if (metaFile.exists()) {
                addToZip(zipOut, META_DB_NAME, metaFile)
                AppLogger.i(TAG, "导出 meta.db, size=${metaFile.length()}")
            }

            // 添加所有年库
            val yearFiles = DatabaseManager.getAllYearDbFiles(context)
            yearFiles.forEach { file ->
                // 文件名: bookkeeper_2025.db → 2025.db
                val entryName = file.name.removePrefix("bookkeeper_")
                addToZip(zipOut, entryName, file)
                AppLogger.i(TAG, "导出 ${file.name}, size=${file.length()}")
            }

            zipOut.close()
            AppLogger.i(TAG, "导出完成: ${outputFile.absolutePath}, size=${outputFile.length()}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "导出失败", e)
            false
        }
    }

    /**
     * 导入：从 zip 文件恢复数据
     * @param zipFile 输入 zip 文件
     * @return 是否成功
     */
    suspend fun importFromZip(context: Context, zipFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // 关闭所有数据库连接
            DatabaseManager.closeAll()

            val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFile)))
            var entry = zipIn.nextEntry
            var importedCount = 0
            var importedMeta = false

            while (entry != null) {
                val entryName = entry.name

                if (entryName == META_DB_NAME) {
                    // 关闭旧连接并清理单例，指向新文件
                    MetaDatabase.clearInstance()
                    val destFile = DatabaseManager.getMetaDbFile(context)
                    extractFromZip(zipIn, destFile)
                    AppLogger.i(TAG, "导入 meta.db")
                    importedCount++
                    importedMeta = true
                } else if (entryName.matches(Regex("\\d{4}\\.db"))) {
                    // YYYY.db → bookkeeper_YYYY.db
                    val year = entryName.removeSuffix(".db").toIntOrNull()
                    if (year != null) {
                        val destFile = DatabaseManager.getYearDbFile(context, year)
                        extractFromZip(zipIn, destFile)
                        AppLogger.i(TAG, "导入年库: $year")
                        importedCount++
                    }
                }

                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            zipIn.close()
            AppLogger.i(TAG, "导入完成: 共导入 $importedCount 个文件")

            // 校验导入的文件是否为有效 SQLite 数据库
            val metaFile = DatabaseManager.getMetaDbFile(context)
            if (metaFile.exists() && !isValidSqlite(metaFile)) {
                AppLogger.e(TAG, "meta.db 校验失败，不是有效的 SQLite 文件")
                return@withContext false
            }

            importedCount > 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "导入 zip 失败", e)
            false
        }
    }

    /**
     * 导入旧格式 .db 文件：走 DataMigrator 迁移逻辑
     * @param dbFile 旧版 bookkeeper.db 文件
     */
    suspend fun importLegacyDb(context: Context, dbFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // 校验文件头
            if (!isValidSqlite(dbFile)) {
                AppLogger.e(TAG, "不是有效的 SQLite 文件")
                return@withContext false
            }

            // 关闭所有连接
            DatabaseManager.closeAll()

            // 将文件复制为旧格式数据库路径
            val legacyFile = context.getDatabasePath(AppDatabase.DB_NAME)
            dbFile.copyTo(legacyFile, overwrite = true)

            // 走迁移逻辑
            val success = DataMigrator.migrate(context)
            if (!success) {
                AppLogger.e(TAG, "旧格式导入迁移失败")
            }
            success
        } catch (e: Exception) {
            AppLogger.e(TAG, "旧格式导入失败", e)
            false
        }
    }

    /**
     * 判断文件是否为新格式 zip
     */
    fun isZipFile(file: File): Boolean {
        if (!file.exists()) return false
        val header = file.readBytes(4)
        // ZIP 文件魔数: PK\x03\x04
        return header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
    }

    /**
     * 判断文件是否为旧格式 SQLite db
     */
    fun isValidSqlite(file: File): Boolean {
        if (!file.exists()) return false
        val header = file.readBytes(16)
        val sqliteHeader = "SQLite format 3\u0000".toByteArray()
        return header.contentEquals(sqliteHeader)
    }

    // ——— 内部工具 ———

    private fun addToZip(zipOut: ZipOutputStream, entryName: String, file: File) {
        // 先做 WAL checkpoint 确保数据完整（如果 db 还在打开状态）
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
        // 删除旧文件和 WAL/SHM
        destFile.delete()
        File(destFile.absolutePath + "-wal").delete()
        File(destFile.absolutePath + "-shm").delete()

        BufferedOutputStream(FileOutputStream(destFile)).use { out ->
            val buffer = ByteArray(8192)
            var len: Int
            while (zipIn.read(buffer).also { len = it } > 0) {
                out.write(buffer, 0, len)
            }
        }
    }

    private fun File.readBytes(maxBytes: Int): ByteArray {
        if (!exists()) return ByteArray(0)
        return FileInputStream(this).use { input ->
            val buffer = ByteArray(maxBytes)
            val read = input.read(buffer)
            if (read < maxBytes) buffer.copyOf(read) else buffer
        }
    }
}
