package com.simplebookkeeper.data

import android.content.Context
import com.simplebookkeeper.security.PasswordManager
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.Saving
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据导入导出工具 — CSV + 加密 ZIP 方案
 *
 * 导出：从 Room 读取数据 → 写入 CSV → 压缩为 ZIP → 可选 AES-256 加密
 * 导入：读取 ZIP → 可选 AES-256 解密 → 解压 → 解析 CSV → 写入 Room
 *
 * ZIP 包结构：
 *   meta.json          ← {"encrypted": true/false, "salt": "base64...", "version": 1}
 *   categories.csv     ← id,name,type,icon,isDefault,sortOrder
 *   transactions.csv   ← id,type,amount,categoryId,paymentMethod,note,date,createdAt,updatedAt
 *   savings.csv        ← id,type,amount,note,date,createdAt,updatedAt
 */
object DataExporter {

    private const val TAG = "DataExporter"
    private const val META_JSON = "meta.json"
    private const val CATEGORIES_CSV = "categories.csv"
    private const val TRANSACTIONS_CSV = "transactions.csv"
    private const val SAVINGS_CSV = "savings.csv"

    // ─── 导出 ─────────────────────────────────────────────────

    /**
     * 导出为 ZIP 文件
     * @param context Context
     * @param outputFile 输出文件
     * @param password 密码，null 表示不加密
     * @return 是否成功
     */
    suspend fun exportToZip(context: Context, outputFile: File, password: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as? com.simplebookkeeper.BookkeeperApp
                    ?: return@withContext false
                val dbManager = app.dbManager

                // 读取所有数据
                val categories = dbManager.categoryDao.getAllSync()
                val transactions = dbManager.transactionDao.getAll()
                val savings = dbManager.savingDao.getAllSync()

                AppLogger.i(TAG, "导出: ${categories.size} 分类, ${transactions.size} 交易, ${savings.size} 储蓄")

                // 生成 ZIP 字节数组
                val zipBytes = createZipBytes(categories, transactions, savings)

                // 根据是否设密码决定是否加密
                val finalBytes = if (password != null) {
                    val salt = PasswordManager.generateSalt()
                    val key = PasswordManager.deriveKey(password, salt)
                    val encrypted = PasswordManager.encryptData(zipBytes, key)
                    val saltB64 = android.util.Base64.encodeToString(salt, android.util.Base64.DEFAULT)

                    // 创建包含 meta.json + 加密数据的最终 ZIP
                    val metaJson = JSONObject().apply {
                        put("encrypted", true)
                        put("salt", saltB64)
                        put("version", 1)
                    }

                    val finalZip = ByteArrayOutputStream()
                    val zipOut = ZipOutputStream(finalZip)
                    zipOut.putNextEntry(ZipEntry(META_JSON))
                    zipOut.write(metaJson.toString().toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                    zipOut.putNextEntry(ZipEntry("data.bin"))
                    zipOut.write(encrypted)
                    zipOut.closeEntry()
                    zipOut.close()
                    finalZip.toByteArray()
                } else {
                    // 不加密，直接将 meta.json 加入 ZIP
                    val metaJson = JSONObject().apply {
                        put("encrypted", false)
                        put("version", 1)
                    }

                    val finalZip = ByteArrayOutputStream()
                    val zipOut = ZipOutputStream(finalZip)
                    zipOut.putNextEntry(ZipEntry(META_JSON))
                    zipOut.write(metaJson.toString().toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                    // 加入原始 CSV ZIP 的内容
                    zipOut.putNextEntry(ZipEntry(CATEGORIES_CSV))
                    zipOut.write(extractCsvFromZip(zipBytes, CATEGORIES_CSV))
                    zipOut.closeEntry()
                    zipOut.putNextEntry(ZipEntry(TRANSACTIONS_CSV))
                    zipOut.write(extractCsvFromZip(zipBytes, TRANSACTIONS_CSV))
                    zipOut.closeEntry()
                    zipOut.putNextEntry(ZipEntry(SAVINGS_CSV))
                    zipOut.write(extractCsvFromZip(zipBytes, SAVINGS_CSV))
                    zipOut.closeEntry()
                    zipOut.close()
                    finalZip.toByteArray()
                }

                outputFile.writeBytes(finalBytes)
                AppLogger.i(TAG, "导出完成: ${outputFile.length()} bytes, encrypted=${password != null}")
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "导出失败", e)
                false
            }
        }

    /**
     * 从 ZIP 文件导入数据
     * @param context Context
     * @param zipFile ZIP 文件
     * @param password 密码，null 表示不加密的 ZIP
     * @return 是否成功
     */
    suspend fun importFromZip(context: Context, zipFile: File, password: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as? com.simplebookkeeper.BookkeeperApp
                    ?: return@withContext false
                val dbManager = app.dbManager

                val zipBytes = zipFile.readBytes()

                // 解析 ZIP，提取 meta.json 和数据
                var metaJson: JSONObject? = null
                var encryptedData: ByteArray? = null
                var csvZipBytes: ByteArray? = null

                val zipIn = ZipInputStream(ByteArrayInputStream(zipBytes))
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryBytes = readZipEntry(zipIn)
                    when (entry.name) {
                        META_JSON -> {
                            metaJson = JSONObject(String(entryBytes, Charsets.UTF_8))
                        }
                        "data.bin" -> {
                            encryptedData = entryBytes
                        }
                        CATEGORIES_CSV, TRANSACTIONS_CSV, SAVINGS_CSV -> {
                            // 不加密的 ZIP，CSV 直接在里面
                            if (csvZipBytes == null) {
                                csvZipBytes = zipBytes
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                zipIn.close()

                val isEncrypted = metaJson?.optBoolean("encrypted", false) ?: false

                // 如果是加密的 ZIP，需要解密
                val dataZipBytes = if (isEncrypted && encryptedData != null) {
                    val saltB64 = metaJson?.optString("salt") ?: return@withContext false
                    val salt = android.util.Base64.decode(saltB64, android.util.Base64.DEFAULT)

                    if (password == null) {
                        AppLogger.e(TAG, "ZIP 已加密但未提供密码")
                        return@withContext false
                    }

                    try {
                        val key = PasswordManager.deriveKey(password, salt)
                        PasswordManager.decryptData(encryptedData, key)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "解密失败（密码不匹配）", e)
                        return@withContext false
                    }
                } else {
                    // 不加密的 ZIP，CSV 已经在 zipBytes 中
                    csvZipBytes ?: return@withContext false
                }

                // 从解密后的 ZIP 或原始 ZIP 中提取 CSV
                val categories = readCategoriesFromCsv(dataZipBytes)
                val transactions = readTransactionsFromCsv(dataZipBytes)
                val savings = readSavingsFromCsv(dataZipBytes)

                AppLogger.i(TAG, "导入: ${categories.size} 分类, ${transactions.size} 交易, ${savings.size} 储蓄")

                // 写入数据库
                // 导入分类时，按 name+type 去重（INSERT OR REPLACE）
                if (categories.isNotEmpty()) {
                    dbManager.categoryDao.deleteAll()
                    dbManager.categoryDao.insertAll(categories)
                }

                // 导入交易：直接插入
                if (transactions.isNotEmpty()) {
                    dbManager.transactionDao.deleteAll()
                    transactions.forEach { dbManager.transactionDao.insert(it) }
                }

                // 导入储蓄：直接插入
                if (savings.isNotEmpty()) {
                    dbManager.savingDao.deleteAll()
                    savings.forEach { dbManager.savingDao.insert(it) }
                }

                // 标记需要重启重新加密
                dbManager.resetForReEncryption()
                AppLogger.i(TAG, "导入完成")
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "导入失败", e)
                false
            }
        }

    /**
     * 读取 ZIP 中的 meta.json，判断是否加密
     */
    fun readMetaFromZip(zipFile: File): JSONObject? {
        return try {
            val zipIn = ZipInputStream(ByteArrayInputStream(zipFile.readBytes()))
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (entry.name == META_JSON) {
                    val bytes = readZipEntry(zipIn)
                    return JSONObject(String(bytes, Charsets.UTF_8))
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            zipIn.close()
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "读取 meta.json 失败", e)
            null
        }
    }

    // ─── 内部方法 ───────────────────────────────────────────────

    /** 创建包含 CSV 的 ZIP 字节数组 */
    private fun createZipBytes(
        categories: List<Category>,
        transactions: List<Transaction>,
        savings: List<Saving>
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val zipOut = ZipOutputStream(baos)

        zipOut.putNextEntry(ZipEntry(CATEGORIES_CSV))
        zipOut.write(writeCategoriesToCsv(categories).toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        zipOut.putNextEntry(ZipEntry(TRANSACTIONS_CSV))
        zipOut.write(writeTransactionsToCsv(transactions).toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        zipOut.putNextEntry(ZipEntry(SAVINGS_CSV))
        zipOut.write(writeSavingsToCsv(savings).toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        zipOut.close()
        return baos.toByteArray()
    }

    /** 从 ZIP 字节数组中提取指定文件的内容 */
    private fun extractCsvFromZip(zipBytes: ByteArray, entryName: String): ByteArray {
        val zipIn = ZipInputStream(ByteArrayInputStream(zipBytes))
        var entry = zipIn.nextEntry
        while (entry != null) {
            if (entry.name == entryName) {
                return readZipEntry(zipIn)
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()
        return ByteArray(0)
    }

    /** 读取 ZIP 条目的全部内容 */
    private fun readZipEntry(zipIn: ZipInputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var len: Int
        while (zipIn.read(buffer).also { len = it } > 0) {
            baos.write(buffer, 0, len)
        }
        return baos.toByteArray()
    }

    // ─── CSV 写入 ──────────────────────────────────────────────

    /** 写分类 CSV */
    private fun writeCategoriesToCsv(categories: List<Category>): String {
        val sb = StringBuilder()
        sb.appendLine("id,name,type,icon,isDefault,sortOrder")
        for (c in categories) {
            sb.appendLine("${c.id},${escapeCsv(c.name)},${c.type.name},${escapeCsv(c.icon)},${if (c.isDefault) 1 else 0},${c.sortOrder}")
        }
        return sb.toString()
    }

    /** 写交易 CSV */
    private fun writeTransactionsToCsv(transactions: List<Transaction>): String {
        val sb = StringBuilder()
        sb.appendLine("id,type,amount,categoryId,paymentMethod,note,date,createdAt,updatedAt")
        for (t in transactions) {
            sb.appendLine("${t.id},${t.type.name},${t.amount},${t.categoryId},${t.paymentMethod.name},${escapeCsv(t.note)},${t.date.time},${t.createdAt},${t.updatedAt}")
        }
        return sb.toString()
    }

    /** 写储蓄 CSV */
    private fun writeSavingsToCsv(savings: List<Saving>): String {
        val sb = StringBuilder()
        sb.appendLine("id,type,amount,note,date,createdAt,updatedAt")
        for (s in savings) {
            sb.appendLine("${s.id},${s.type.name},${s.amount},${escapeCsv(s.note)},${s.date.time},${s.createdAt},${s.updatedAt}")
        }
        return sb.toString()
    }

    // ─── CSV 读取 ──────────────────────────────────────────────

    /** 从 ZIP 字节数组读取分类 */
    private fun readCategoriesFromCsv(zipBytes: ByteArray): List<Category> {
        val csvBytes = extractCsvFromZip(zipBytes, CATEGORIES_CSV)
        if (csvBytes.isEmpty()) return emptyList()
        val reader = InputStreamReader(ByteArrayInputStream(csvBytes), Charsets.UTF_8)
        val lines = reader.readLines()
        reader.close()
        if (lines.size <= 1) return emptyList() // 只有 header

        val result = mutableListOf<Category>()
        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            if (fields.size < 6) continue
            try {
                result.add(
                    Category(
                        id = fields[0].toLongOrNull() ?: continue,
                        name = fields[1],
                        type = try { com.simplebookkeeper.data.model.TransactionType.valueOf(fields[2]) } catch (_: Exception) { continue },
                        icon = fields[3],
                        isDefault = fields[4] == "1",
                        sortOrder = fields[5].toIntOrNull() ?: 0
                    )
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "解析分类行失败: ${lines[i]}", e)
            }
        }
        return result
    }

    /** 从 ZIP 字节数组读取交易 */
    private fun readTransactionsFromCsv(zipBytes: ByteArray): List<Transaction> {
        val csvBytes = extractCsvFromZip(zipBytes, TRANSACTIONS_CSV)
        if (csvBytes.isEmpty()) return emptyList()
        val reader = InputStreamReader(ByteArrayInputStream(csvBytes), Charsets.UTF_8)
        val lines = reader.readLines()
        reader.close()
        if (lines.size <= 1) return emptyList()

        val result = mutableListOf<Transaction>()
        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            if (fields.size < 9) continue
            try {
                result.add(
                    Transaction(
                        id = fields[0].toLongOrNull() ?: continue,
                        type = try { com.simplebookkeeper.data.model.TransactionType.valueOf(fields[1]) } catch (_: Exception) { continue },
                        amount = fields[2].toLongOrNull() ?: continue,
                        categoryId = fields[3].toLongOrNull() ?: continue,
                        paymentMethod = try { com.simplebookkeeper.data.model.PaymentMethod.valueOf(fields[4]) } catch (_: Exception) { continue },
                        note = fields[5],
                        date = java.util.Date(fields[6].toLongOrNull() ?: continue),
                        createdAt = fields[7].toLongOrNull() ?: continue,
                        updatedAt = fields[8].toLongOrNull() ?: continue
                    )
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "解析交易行失败: ${lines[i]}", e)
            }
        }
        return result
    }

    /** 从 ZIP 字节数组读取储蓄 */
    private fun readSavingsFromCsv(zipBytes: ByteArray): List<Saving> {
        val csvBytes = extractCsvFromZip(zipBytes, SAVINGS_CSV)
        if (csvBytes.isEmpty()) return emptyList()
        val reader = InputStreamReader(ByteArrayInputStream(csvBytes), Charsets.UTF_8)
        val lines = reader.readLines()
        reader.close()
        if (lines.size <= 1) return emptyList()

        val result = mutableListOf<Saving>()
        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            if (fields.size < 7) continue
            try {
                result.add(
                    Saving(
                        id = fields[0].toLongOrNull() ?: continue,
                        type = try { com.simplebookkeeper.data.model.SavingType.valueOf(fields[1]) } catch (_: Exception) { continue },
                        amount = fields[2].toLongOrNull() ?: continue,
                        note = fields[3],
                        date = java.util.Date(fields[4].toLongOrNull() ?: continue),
                        createdAt = fields[5].toLongOrNull() ?: continue,
                        updatedAt = fields[6].toLongOrNull() ?: continue
                    )
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "解析储蓄行失败: ${lines[i]}", e)
            }
        }
        return result
    }

    // ─── CSV 工具 ──────────────────────────────────────────────

    /** 标准 CSV 转义：包含逗号、换行、双引号的字段用双引号包裹，内部双引号翻倍 */
    private fun escapeCsv(field: String): String {
        if (field.contains(',') || field.contains('\n') || field.contains('\r') || field.contains('"')) {
            return "\"${field.replace("\"", "\"\"")}\""
        }
        return field
    }

    /** 解析 CSV 行，处理双引号包裹的字段 */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val len = line.length

        while (i <= len) {
            if (i == len) {
                // 最后一个空字段
                result.add("")
                break
            }

            if (line[i] == '"') {
                // 双引号包裹的字段
                val sb = StringBuilder()
                i++ // 跳过开头的 "
                while (i < len) {
                    if (line[i] == '"') {
                        if (i + 1 < len && line[i + 1] == '"') {
                            // 双引号翻倍 → 单个双引号
                            sb.append('"')
                            i += 2
                        } else {
                            // 结束引号
                            i++ // 跳过结尾的 "
                            break
                        }
                    } else {
                        sb.append(line[i])
                        i++
                    }
                }
                result.add(sb.toString())
                // 跳过逗号
                if (i < len && line[i] == ',') i++
            } else {
                // 非引号字段
                val commaIndex = line.indexOf(',', i)
                if (commaIndex == -1) {
                    result.add(line.substring(i))
                    break
                } else {
                    result.add(line.substring(i, commaIndex))
                    i = commaIndex + 1
                }
            }
        }

        return result
    }
}
