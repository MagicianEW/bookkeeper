package com.simplebookkeeper.data

import android.content.Context
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.PaymentMethodEntity
import com.simplebookkeeper.data.model.Saving
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader

/**
 * 数据导入导出工具 — CSV + ZIP 方案
 *
 * ZIP 包结构（始终为明文 CSV，加密在 ZIP 层）：
 *   meta.json          ← {"encrypted": true/false, "version": 1}
 *   categories.csv     ← id,name,type,icon,isDefault,sortOrder
 *   transactions.csv   ← id,type,amount,categoryId,paymentMethod,customPaymentMethod,note,date,createdAt,updatedAt
 *   savings.csv        ← id,type,amount,note,date,createdAt,updatedAt
 *   payment_methods.csv ← id,name,type,isDefault
 *
 * 加密方式：Zip4j AES-256（标准 ZIP 加密，解压时需输入密码）
 * 不加密时：普通 ZIP，可直接解压查看 CSV
 */
object DataExporter {

    private const val TAG = "DataExporter"
    private const val META_JSON = "meta.json"
    private const val CATEGORIES_CSV = "categories.csv"
    private const val TRANSACTIONS_CSV = "transactions.csv"
    private const val SAVINGS_CSV = "savings.csv"
    private const val PAYMENT_METHODS_CSV = "payment_methods.csv"

    // ─── 导出 ─────────────────────────────────────────────────

    /**
     * 导出为 ZIP 文件
     * @param context Context
     * @param outputFile 输出文件（会被覆盖）
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
                val paymentMethods = dbManager.paymentMethodDao.getAllSync()

                AppLogger.i(TAG, "导出: ${categories.size} 分类, ${transactions.size} 交易, ${savings.size} 储蓄, ${paymentMethods.size} 支付方式")

                // 删除旧文件，Zip4j 不接受已存在的文件（会抛异常）
                if (outputFile.exists()) outputFile.delete()

                val isEncrypted = password != null

                // 构建 meta.json 内容
                val metaJson = JSONObject().apply {
                    put("encrypted", isEncrypted)
                    put("version", 1)
                }.toString().toByteArray(Charsets.UTF_8)

                // 各 CSV 内容
                val categoriesCsv = writeCategoriesToCsv(categories).toByteArray(Charsets.UTF_8)
                val transactionsCsv = writeTransactionsToCsv(transactions).toByteArray(Charsets.UTF_8)
                val savingsCsv = writeSavingsToCsv(savings).toByteArray(Charsets.UTF_8)
                val paymentMethodsCsv = writePaymentMethodsToCsv(paymentMethods).toByteArray(Charsets.UTF_8)

                if (isEncrypted) {
                    // 使用 Zip4j AES-256 加密
                    val zipFile = ZipFile(outputFile, password!!.toCharArray())
                    val params = ZipParameters().apply {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }

                    // 依次添加各文件
                    params.fileNameInZip = META_JSON
                    zipFile.addStream(ByteArrayInputStream(metaJson), params)

                    params.fileNameInZip = CATEGORIES_CSV
                    zipFile.addStream(ByteArrayInputStream(categoriesCsv), params)

                    params.fileNameInZip = TRANSACTIONS_CSV
                    zipFile.addStream(ByteArrayInputStream(transactionsCsv), params)

                    params.fileNameInZip = SAVINGS_CSV
                    zipFile.addStream(ByteArrayInputStream(savingsCsv), params)

                    params.fileNameInZip = PAYMENT_METHODS_CSV
                    zipFile.addStream(ByteArrayInputStream(paymentMethodsCsv), params)

                } else {
                    // 普通 ZIP，无加密
                    val zipFile = ZipFile(outputFile)
                    val params = ZipParameters()

                    params.fileNameInZip = META_JSON
                    zipFile.addStream(ByteArrayInputStream(metaJson), params)

                    params.fileNameInZip = CATEGORIES_CSV
                    zipFile.addStream(ByteArrayInputStream(categoriesCsv), params)

                    params.fileNameInZip = TRANSACTIONS_CSV
                    zipFile.addStream(ByteArrayInputStream(transactionsCsv), params)

                    params.fileNameInZip = SAVINGS_CSV
                    zipFile.addStream(ByteArrayInputStream(savingsCsv), params)

                    params.fileNameInZip = PAYMENT_METHODS_CSV
                    zipFile.addStream(ByteArrayInputStream(paymentMethodsCsv), params)
                }

                AppLogger.i(TAG, "导出完成: ${outputFile.length()} bytes, encrypted=$isEncrypted")
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

                // 先读 meta.json 判断是否加密
                val meta = readMetaFromZip(zipFile)
                val isEncrypted = meta?.optBoolean("encrypted", false) ?: false

                AppLogger.i(TAG, "导入: isEncrypted=$isEncrypted, passwordProvided=${password != null}")

                val zip = if (isEncrypted) {
                    if (password == null) {
                        AppLogger.e(TAG, "ZIP 已加密但未提供密码")
                        return@withContext false
                    }
                    try {
                        ZipFile(zipFile, password.toCharArray())
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "解密失败（密码不匹配）", e)
                        return@withContext false
                    }
                } else {
                    ZipFile(zipFile)
                }

                // 验证密码正确性（尝试读取一个条目）
                if (isEncrypted) {
                    try {
                        zip.getInputStream(zip.getFileHeader(META_JSON))?.close()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "密码错误", e)
                        return@withContext false
                    }
                }

                // 读取各 CSV
                val categories = readCsvFromZip(zip, CATEGORIES_CSV)?.let { parseCategoriesCsv(it) } ?: emptyList()
                val transactions = readCsvFromZip(zip, TRANSACTIONS_CSV)?.let { parseTransactionsCsv(it) } ?: emptyList()
                val savings = readCsvFromZip(zip, SAVINGS_CSV)?.let { parseSavingsCsv(it) } ?: emptyList()
                val paymentMethods = readCsvFromZip(zip, PAYMENT_METHODS_CSV)?.let { parsePaymentMethodsCsv(it) } ?: emptyList()

                AppLogger.i(TAG, "导入: ${categories.size} 分类, ${transactions.size} 交易, ${savings.size} 储蓄, ${paymentMethods.size} 支付方式")

                // 写入数据库（事务保护，防止部分写入）
                dbManager.db.withTransaction {
                    if (categories.isNotEmpty()) {
                        dbManager.categoryDao.deleteAll()
                        dbManager.categoryDao.insertAll(categories)
                    }
                    if (transactions.isNotEmpty()) {
                        dbManager.transactionDao.deleteAll()
                        transactions.forEach { dbManager.transactionDao.insert(it) }
                    }
                    if (savings.isNotEmpty()) {
                        dbManager.savingDao.deleteAll()
                        savings.forEach { dbManager.savingDao.insert(it) }
                    }
                    if (paymentMethods.isNotEmpty()) {
                        // 清理旧的自定义方式，保留预置方式
                        dbManager.paymentMethodDao.deleteAllCustom()
                        paymentMethods.forEach { dbManager.paymentMethodDao.insert(it) }
                    }
                }

                dbManager.resetForReEncryption()
                AppLogger.i(TAG, "导入完成")
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "导入失败", e)
                false
            }
        }

    /**
     * 读取 ZIP 中的 meta.json，判断是否加密（无需密码）
     */
    fun readMetaFromZip(zipFile: File): JSONObject? {
        return try {
            // meta.json 本身是未加密的（即使整个 ZIP 加密），
            // 但 Zip4j 中如果整个 ZIP 加密，meta.json 也会加密——
            // 因此先尝试无密码读，失败则返回 encrypted=true 的默认值
            val zip = ZipFile(zipFile)
            val header = zip.getFileHeader(META_JSON) ?: return null
            val bytes = zip.getInputStream(header).use { it.readBytes() }
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            // 读取 meta.json 失败（可能是加密的），假定已加密
            AppLogger.i(TAG, "readMetaFromZip: 无法明文读取（可能已加密）")
            JSONObject().apply { put("encrypted", true) }
        }
    }

    // ─── 内部：从 Zip4j ZipFile 读取 CSV 内容 ─────────────────

    private fun readCsvFromZip(zip: ZipFile, entryName: String): String? {
        return try {
            val header = zip.getFileHeader(entryName) ?: return null
            val bytes = zip.getInputStream(header).use { it.readBytes() }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e(TAG, "读取 $entryName 失败", e)
            null
        }
    }

    // ─── CSV 写入 ──────────────────────────────────────────────

    private fun writeCategoriesToCsv(categories: List<Category>): String {
        val sb = StringBuilder()
        sb.appendLine("id,name,type,icon,isDefault,sortOrder")
        for (c in categories) {
            sb.appendLine("${c.id},${escapeCsv(c.name)},${c.type.name},${escapeCsv(c.icon)},${if (c.isDefault) 1 else 0},${c.sortOrder}")
        }
        return sb.toString()
    }

    private fun writeTransactionsToCsv(transactions: List<Transaction>): String {
        val sb = StringBuilder()
        sb.appendLine("id,type,amount,categoryId,paymentMethod,customPaymentMethod,note,date,createdAt,updatedAt")
        for (t in transactions) {
            sb.appendLine("${t.id},${t.type.name},${t.amount},${t.categoryId},${t.paymentMethod.name},${escapeCsv(t.customPaymentMethod ?: "")},${escapeCsv(t.note)},${t.date.time},${t.createdAt},${t.updatedAt}")
        }
        return sb.toString()
    }

    private fun writeSavingsToCsv(savings: List<Saving>): String {
        val sb = StringBuilder()
        sb.appendLine("id,type,amount,note,date,createdAt,updatedAt")
        for (s in savings) {
            sb.appendLine("${s.id},${s.type.name},${s.amount},${escapeCsv(s.note)},${s.date.time},${s.createdAt},${s.updatedAt}")
        }
        return sb.toString()
    }

    private fun writePaymentMethodsToCsv(methods: List<PaymentMethodEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("id,name,type,isDefault")
        for (m in methods) {
            sb.appendLine("${m.id},${escapeCsv(m.name)},${m.type.name},${if (m.isDefault) 1 else 0}")
        }
        return sb.toString()
    }

    // ─── CSV 解析 ──────────────────────────────────────────────

    private fun parseCategoriesCsv(csv: String): List<Category> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()
        val result = mutableListOf<Category>()
        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            if (fields.size < 6) continue
            try {
                result.add(Category(
                    id = fields[0].toLongOrNull() ?: continue,
                    name = fields[1],
                    type = try { com.simplebookkeeper.data.model.TransactionType.valueOf(fields[2]) } catch (_: Exception) { continue },
                    icon = fields[3],
                    isDefault = fields[4] == "1",
                    sortOrder = fields[5].toIntOrNull() ?: 0
                ))
            } catch (e: Exception) {
                AppLogger.w(TAG, "解析分类行失败: ${lines[i]}", e)
            }
        }
        return result
    }

    private fun parseTransactionsCsv(csv: String): List<Transaction> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()
        val result = mutableListOf<Transaction>()
        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            if (fields.size < 9) continue
            try {
                result.add(Transaction(
                    id = fields[0].toLongOrNull() ?: continue,
                    type = try { com.simplebookkeeper.data.model.TransactionType.valueOf(fields[1]) } catch (_: Exception) { continue },
                    amount = fields[2].toLongOrNull() ?: continue,
                    categoryId = fields[3].toLongOrNull() ?: continue,
                    paymentMethod = try { com.simplebookkeeper.data.model.PaymentMethod.valueOf(fields[4]) } catch (_: Exception) { continue },
                    customPaymentMethod = fields[5].ifBlank { null },
                    note = fields[6],
                    date = java.util.Date(fields[7].toLongOrNull() ?: continue),
                    createdAt = fields[8].toLongOrNull() ?: continue,
                    updatedAt = if (fields.size > 9) fields[9].toLongOrNull() ?: continue else continue
                ))
            } catch (e: Exception) {
                AppLogger.w(TAG, "解析交易行失败: ${lines[i]}", e)
            }
        }
        return result
    }

    private fun parseSavingsCsv(csv: String): List<Saving> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()
        val result = mutableListOf<Saving>()
        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            if (fields.size < 7) continue
            try {
                result.add(Saving(
                    id = fields[0].toLongOrNull() ?: continue,
                    type = try { com.simplebookkeeper.data.model.SavingType.valueOf(fields[1]) } catch (_: Exception) { continue },
                    amount = fields[2].toLongOrNull() ?: continue,
                    note = fields[3],
                    date = java.util.Date(fields[4].toLongOrNull() ?: continue),
                    createdAt = fields[5].toLongOrNull() ?: continue,
                    updatedAt = fields[6].toLongOrNull() ?: continue
                ))
            } catch (e: Exception) {
                AppLogger.w(TAG, "解析储蓄行失败: ${lines[i]}", e)
            }
        }
        return result
    }

    private fun parsePaymentMethodsCsv(csv: String): List<PaymentMethodEntity> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()
        val result = mutableListOf<PaymentMethodEntity>()
        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            if (fields.size < 4) continue
            try {
                result.add(PaymentMethodEntity(
                    id = fields[0].toLongOrNull() ?: 0,
                    name = fields[1],
                    type = try { com.simplebookkeeper.data.model.TransactionType.valueOf(fields[2]) } catch (_: Exception) { continue },
                    isDefault = fields[3] == "1"
                ))
            } catch (e: Exception) {
                AppLogger.w(TAG, "解析支付方式行失败: ${lines[i]}", e)
            }
        }
        return result
    }

    // ─── CSV 工具 ──────────────────────────────────────────────

    private fun escapeCsv(field: String): String {
        if (field.contains(',') || field.contains('\n') || field.contains('\r') || field.contains('"')) {
            return "\"${field.replace("\"", "\"\"")}\""
        }
        return field
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val len = line.length

        while (i <= len) {
            if (i == len) {
                result.add("")
                break
            }

            if (line[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < len) {
                    if (line[i] == '"') {
                        if (i + 1 < len && line[i + 1] == '"') {
                            sb.append('"')
                            i += 2
                        } else {
                            i++
                            break
                        }
                    } else {
                        sb.append(line[i])
                        i++
                    }
                }
                result.add(sb.toString())
                if (i < len && line[i] == ',') i++
            } else {
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
