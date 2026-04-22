package com.simplebookkeeper.sync

import android.content.Context
import com.simplebookkeeper.data.DatabaseManager
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.MetaDatabase
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ─── 同步结果类型 ───────────────────────────────────────────────

sealed class SyncResult {
    object Success : SyncResult()

    /** 本地与远程大小不一致（且 MD5 不同），需要用户确认后才上传 */
    data class SizeMismatch(val conflictFiles: List<ConflictFile>) : SyncResult()

    data class Conflict(val localTime: Long, val remoteTime: Long) : SyncResult()
    data class MultiConflict(val conflictFiles: List<ConflictFile>) : SyncResult()
    data class Error(val message: String) : SyncResult()
    
    /** 需要用户选择备份版本 */
    data class SelectBackup(val versions: List<BackupVersion>) : SyncResult()
}

data class ConflictFile(
    val fileName: String,
    val localMd5: String,
    val remoteMd5: String
)

data class BackupVersion(
    val fileName: String,          // e.g., "meta.db_2026-04-20.bak"
    val displayName: String,       // e.g., "2026-04-20"
    val timestamp: Long            // 文件修改时间
)

// ─── WebDAV 管理器 ───────────────────────────────────────────────

class WebDavManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val REMOTE_FOLDER = "bookkeeper"
    private val VERSION_FILE = ".version"
    private val CURRENT_VERSION = "2"

    private fun baseUrl(config: WebDavConfig): String =
        config.url.trimEnd('/') + "/$REMOTE_FOLDER"

    private fun remoteFileUrl(config: WebDavConfig, fileName: String): String =
        "${baseUrl(config)}/$fileName"

    /** 备份远程文件：rename 为 file_YYYY-MM-DD.bak，然后上传新文件 */
    private suspend fun uploadWithBackup(
        config: WebDavConfig,
        fileName: String,
        localFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. 检查远程文件是否存在，存在则备份
            if (remoteFileExists(config, fileName)) {
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val backupName = "${fileName}_$dateStr.bak"
                AppLogger.i(TAG, "uploadWithBackup: 备份 $fileName → $backupName")
                // MOVE 远程文件到备份名
                if (!moveRemoteFile(config, fileName, backupName)) {
                    AppLogger.w(TAG, "uploadWithBackup: 备份失败，继续上传（可能已有同名备份）")
                }
                // 清理 5 天前的备份
                cleanupOldBackups(config, fileName)
            }
            // 2. 上传新文件
            uploadFile(config, fileName, localFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "uploadWithBackup 异常: $fileName", e)
            false
        }
    }

    /** MOVE 远程文件（rename） */
    private suspend fun moveRemoteFile(
        config: WebDavConfig,
        srcName: String,
        destName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val request = Request.Builder()
                .url(remoteFileUrl(config, srcName))
                .header("Authorization", creds)
                .header("Destination", remoteFileUrl(config, destName))
                .method("MOVE", null)
                .build()
            val response = client.newCall(request).execute()
            response.code in 200..299 || response.code == 201
        } catch (e: Exception) {
            AppLogger.e(TAG, "moveRemoteFile 失败", e)
            false
        }
    }

    /** 清理某文件的旧备份，只保留最近 5 份 */
    private suspend fun cleanupOldBackups(config: WebDavConfig, fileName: String) =
        withContext(Dispatchers.IO) {
            try {
                val datePattern = Regex("""${Regex.escape(fileName)}_(\d{4}-\d{2}-\d{2})\.bak""")
                val remoteFiles = getRemoteFileList(config)
                val backups = remoteFiles.filter { datePattern.matches(it) }
                    .sortedByDescending { datePattern.find(it)?.groupValues?.get(1) ?: "" }
                    .drop(4) // 保留最新 4 份，删掉更旧的
                backups.forEach { backupName ->
                    AppLogger.i(TAG, "cleanupOldBackups: 删除旧备份 $backupName")
                    deleteRemoteFile(config, backupName)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "cleanupOldBackups 异常", e)
            }
        }

    /** 删除远程文件 */
    private suspend fun deleteRemoteFile(config: WebDavConfig, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val creds = Credentials.basic(config.username, config.password)
                val request = Request.Builder()
                    .url(remoteFileUrl(config, fileName))
                    .header("Authorization", creds)
                    .delete()
                    .build()
                client.newCall(request).execute().code in 200..299
            } catch (e: Exception) {
                AppLogger.e(TAG, "deleteRemoteFile 失败: $fileName", e)
                false
            }
        }

    // ─── 连接测试 ────────────────────────────────────────────────

    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "testConnection → ${config.url}")
        try {
            val creds = Credentials.basic(config.username, config.password)
            val body = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(config.url.trimEnd('/'))
                .header("Authorization", creds)
                .header("Depth", "0")
                .method("PROPFIND", body)
                .build()
            val response = client.newCall(request).execute()
            if (response.code in 200..299 || response.code == 207) Result.success(Unit)
            else Result.failure(IOException("服务器返回 ${response.code}"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "testConnection 异常", e)
            Result.failure(e)
        }
    }

    // ─── 文件操作 ─────────────────────────────────────────────────

    private suspend fun ensureRemoteFolder(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val creds = Credentials.basic(config.username, config.password)
        val folderUrl = baseUrl(config)

        val checkReq = Request.Builder()
            .url(folderUrl)
            .header("Authorization", creds)
            .header("Depth", "0")
            .method("PROPFIND", """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
                .toRequestBody("application/xml".toMediaType()))
            .build()
        val checkResp = runCatching { client.newCall(checkReq).execute() }.getOrNull()

        if (checkResp?.code == 207 || checkResp?.code == 200) return@withContext

        AppLogger.i(TAG, "ensureRemoteFolder → MKCOL $folderUrl")
        val mkcolReq = Request.Builder()
            .url(folderUrl)
            .header("Authorization", creds)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        runCatching { client.newCall(mkcolReq).execute() }
    }

    private fun fileMd5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var len: Int
            while (input.read(buf).also { len = it } > 0) md.update(buf, 0, len)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun uploadFile(config: WebDavConfig, fileName: String, file: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val creds = Credentials.basic(config.username, config.password)
                val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
                val request = Request.Builder()
                    .url(remoteFileUrl(config, fileName))
                    .header("Authorization", creds)
                    .put(requestBody)
                    .build()
                val response = client.newCall(request).execute()
                response.code in 200..299
            } catch (e: Exception) {
                AppLogger.e(TAG, "uploadFile 失败: $fileName", e)
                false
            }
        }

    private suspend fun downloadFile(config: WebDavConfig, fileName: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val creds = Credentials.basic(config.username, config.password)
                val request = Request.Builder()
                    .url(remoteFileUrl(config, fileName))
                    .header("Authorization", creds)
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                if (response.code == 200) {
                    response.body?.byteStream()?.use { input ->
                        destFile.outputStream().use { out -> input.copyTo(out) }
                    }
                    true
                } else false
            } catch (e: Exception) {
                AppLogger.e(TAG, "downloadFile 失败: $fileName", e)
                false
            }
        }

    private suspend fun remoteFileExists(config: WebDavConfig, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val creds = Credentials.basic(config.username, config.password)
                val body = """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
                    .toRequestBody("application/xml".toMediaType())
                val request = Request.Builder()
                    .url(remoteFileUrl(config, fileName))
                    .header("Authorization", creds)
                    .header("Depth", "0")
                    .method("PROPFIND", body)
                    .build()
                val response = client.newCall(request).execute()
                response.code == 207 || response.code == 200
            } catch (e: Exception) { false }
        }

    private suspend fun getRemoteLastModified(config: WebDavConfig, fileName: String): Long? =
        withContext(Dispatchers.IO) {
            try {
                val creds = Credentials.basic(config.username, config.password)
                val body = """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><getlastmodified/></prop></propfind>"""
                    .toRequestBody("application/xml".toMediaType())
                val request = Request.Builder()
                    .url(remoteFileUrl(config, fileName))
                    .header("Authorization", creds)
                    .header("Depth", "0")
                    .method("PROPFIND", body)
                    .build()
                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: return@withContext null
                val regex = Regex("<[^>]*getlastmodified[^>]*>([^<]+)</[^>]*>", RegexOption.IGNORE_CASE)
                val match = regex.find(html)?.groupValues?.getOrNull(1) ?: return@withContext null
                java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    .parse(match.trim())?.time
            } catch (e: Exception) { null }
        }

    suspend fun getRemoteFileList(config: WebDavConfig): List<String> = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val body = """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><displayname/></prop></propfind>"""
                .toRequestBody("application/xml".toMediaType())
            val request = Request.Builder()
                .url(baseUrl(config))
                .header("Authorization", creds)
                .header("Depth", "1")
                .method("PROPFIND", body)
                .build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext emptyList()
            val regex = Regex("<d:href[^>]*>([^<]+)</d:href>", RegexOption.IGNORE_CASE)
            regex.findAll(html)
                .map { it.groupValues[1].substringAfterLast("/").substringAfterLast("%2F").substringAfterLast("%2f") }
                .filter { it.isNotBlank() && it != REMOTE_FOLDER }
                .toList()
        } catch (e: Exception) {
            AppLogger.e(TAG, "getRemoteFileList 异常", e)
            emptyList()
        }
    }

    /** 获取远程备份版本列表（按时间倒序） */
    suspend fun getRemoteBackups(config: WebDavConfig): List<BackupVersion> = withContext(Dispatchers.IO) {
        try {
            val remoteFiles = getRemoteFileList(config)
            val bakPattern = Regex("""(.+?)_(\d{4}-\d{2}-\d{2})\.bak""")
            remoteFiles.mapNotNull { fileName ->
                val match = bakPattern.matchEntire(fileName) ?: return@mapNotNull null
                val dateStr = match.groupValues[2]
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)
                BackupVersion(
                    fileName = fileName,
                    displayName = dateStr,
                    timestamp = date?.time ?: 0L
                )
            }.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            AppLogger.e(TAG, "getRemoteBackups 异常", e)
            emptyList()
        }
    }

    /** 从指定备份版本恢复（下载 .bak 文件覆盖当前） */
    suspend fun restoreFromBackup(
        config: WebDavConfig,
        dbManager: DatabaseManager,
        backup: BackupVersion
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "restoreFromBackup: 从 ${backup.fileName} 恢复")
            DatabaseManager.closeAll()
            MetaDatabase.clearInstance()
            dbManager.close()
            kotlinx.coroutines.delay(100)

            // 下载 .bak 文件，去掉 .bak 后缀作为目标文件名
            val originalName = backup.fileName.removeSuffix(".bak")
                .substringBeforeLast("_") + ".db"
            val destFile: File = if (originalName == "meta.db") {
                dbManager.metaDbFile
            } else {
                val year = originalName.removePrefix("bookkeeper_").removeSuffix(".db").toIntOrNull()
                    ?: return@withContext SyncResult.Error("无法解析备份文件名: ${backup.fileName}")
                dbManager.getYearDbFile(year)
            }

            if (downloadFile(config, backup.fileName, destFile)) {
                dbManager.invalidateAllYearDbs()
                MetaDatabase.clearInstance()
                AppLogger.i(TAG, "restoreFromBackup: 恢复成功 ${backup.fileName} → ${destFile.name}")
                SyncResult.Success
            } else {
                SyncResult.Error("下载备份失败: ${backup.fileName}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "restoreFromBackup 异常", e)
            SyncResult.Error(e.message ?: "恢复异常")
        }
    }

    private suspend fun getRemoteVersion(config: WebDavConfig): String? = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val request = Request.Builder()
                .url(remoteFileUrl(config, VERSION_FILE))
                .header("Authorization", creds)
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.code == 200) response.body?.string()?.trim() else null
        } catch (e: Exception) { null }
    }

    private suspend fun uploadVersionFile(config: WebDavConfig): Boolean =
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "version_tmp")
            temp.writeText(CURRENT_VERSION)
            uploadFile(config, VERSION_FILE, temp).also { temp.delete() }
        }

    // ─── 多文件同步（推荐使用） ─────────────────────────────────

    /**
     * 多文件同步：关闭所有 DB 连接后逐文件 MD5 比对上传
     */
    suspend fun syncMulti(config: WebDavConfig, dbManager: DatabaseManager): SyncResult =
        withContext(Dispatchers.IO) {
            AppLogger.i(TAG, "syncMulti → 开始")
            try {
                DatabaseManager.closeAll()
                MetaDatabase.clearInstance()
                dbManager.close()
                kotlinx.coroutines.delay(100) // 等待 WAL 文件锁释放
                ensureRemoteFolder(config)

                val metaFile = dbManager.metaDbFile
                val yearFiles = dbManager.getAllYearDbFiles()

                if (!metaFile.exists() && yearFiles.isEmpty()) {
                    return@withContext SyncResult.Error("本地无数据")
                }

                // 首次同步写入版本标记
                if (getRemoteVersion(config) == null) {
                    uploadVersionFile(config)
                }

                val conflicts = mutableListOf<ConflictFile>()
                val localLargerFiles = mutableListOf<ConflictFile>()

                /** 同步单个文件的核心逻辑 */
                suspend fun syncSingleFile(
                    localFile: File,
                    remoteName: String
                ): Boolean {
                    val tmp = File(context.cacheDir, "remote_$remoteName")
                    val remoteExists = downloadFile(config, remoteName, tmp)
                    val localSize = localFile.length()
                    val remoteSize = if (remoteExists) tmp.length() else 0L

                    // 本地为空的情况
                    if (localSize == 0L) {
                        if (!remoteExists || remoteSize == 0L) {
                            // 两边都空或远程不存在，跳过
                            AppLogger.i(TAG, "syncSingleFile: $remoteName 两边都空，跳过")
                            tmp.delete()
                            return true
                        }
                        // 远程有数据但本地空 → 需要用户确认是否下载覆盖
                        AppLogger.w(TAG, "syncSingleFile: $remoteName 本地为空但远程有 ${remoteSize}B 数据")
                        tmp.delete()
                        // 不加入冲突列表，让 syncMulti 返回 Success
                        // 用户下次 downloadMulti 会下载远程数据
                        return true
                    }

                    if (!remoteExists) {
                        // 远程不存在，直接上传（带版本备份）
                        tmp.delete()
                        return uploadWithBackup(config, remoteName, localFile)
                    }

                    // 两边都有数据，比较文件大小
                    if (localSize != remoteSize) {
                        // 大小不一致：需要用户确认
                        val localMd5 = fileMd5(localFile)
                        val remoteMd5 = fileMd5(tmp)
                        if (localMd5 != remoteMd5) {
                            localLargerFiles.add(ConflictFile(remoteName, localMd5, remoteMd5))
                        }
                        tmp.delete()
                        return true
                    }

                    // 大小相同，比较 MD5
                    val localMd5 = fileMd5(localFile)
                    val remoteMd5 = fileMd5(tmp)
                    tmp.delete()

                    if (localMd5 == remoteMd5) {
                        // 完全相同，什么都不做
                        return true
                    }

                    // 大小相同但 MD5 不同：备份远程后上传本地
                    conflicts.add(ConflictFile(remoteName, localMd5, remoteMd5))
                    return uploadWithBackup(config, remoteName, localFile)
                }

                // 同步 meta.db
                if (metaFile.exists()) {
                    if (!syncSingleFile(metaFile, "meta.db")) {
                        return@withContext SyncResult.Error("上传 meta.db 失败")
                    }
                }

                // 同步各年库
                for (yearFile in yearFiles) {
                    if (!syncSingleFile(yearFile, yearFile.name)) {
                        return@withContext SyncResult.Error("上传 ${yearFile.name} 失败")
                    }
                }

                uploadVersionFile(config)

                // 返回结果优先级：大小不一致 > 普通冲突 > 全部成功
                when {
                    localLargerFiles.isNotEmpty() -> {
                        AppLogger.i(TAG, "syncMulti: ${localLargerFiles.size} 个文件大小不一致，待确认")
                        SyncResult.SizeMismatch(localLargerFiles)
                    }
                    conflicts.isNotEmpty() -> {
                        AppLogger.i(TAG, "syncMulti: ${conflicts.size} 个文件冲突（已自动解决）")
                        SyncResult.Success
                    }
                    else -> {
                        AppLogger.i(TAG, "syncMulti: 全部成功（无变化）")
                        SyncResult.Success
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "syncMulti 异常", e)
                SyncResult.Error(e.message ?: "同步异常")
            }
        }

    /**
     * 多文件下载：下载远程所有文件到本地
     */
    suspend fun downloadMulti(config: WebDavConfig, dbManager: DatabaseManager): SyncResult =
        withContext(Dispatchers.IO) {
            AppLogger.i(TAG, "downloadMulti → 开始")
            try {
                DatabaseManager.closeAll()
                MetaDatabase.clearInstance()
                dbManager.close()
                kotlinx.coroutines.delay(100)
                ensureRemoteFolder(config)

                val remoteFiles = getRemoteFileList(config)
                var downloaded = 0

                for (fileName in remoteFiles) {
                    if (fileName == VERSION_FILE || !fileName.endsWith(".db")) continue
                    if (fileName != "meta.db" && !fileName.matches(Regex("""bookkeeper_\d{4}\.db"""))) continue

                    val destFile: File = if (fileName == "meta.db") {
                        dbManager.metaDbFile
                    } else {
                        val year = fileName.removePrefix("bookkeeper_").removeSuffix(".db").toIntOrNull() ?: continue
                        dbManager.getYearDbFile(year)
                    }

                    if (downloadFile(config, fileName, destFile)) {
                        downloaded++
                        AppLogger.i(TAG, "downloadMulti: 已下载 $fileName")
                    }
                }

                if (downloaded == 0) SyncResult.Error("REMOTE_NOT_FOUND")
                else {
                    // 清除所有 DB 缓存（Room 会自动执行 MIGRATION_1_2）
                    dbManager.invalidateAllYearDbs()
                    MetaDatabase.clearInstance()
                    AppLogger.i(TAG, "downloadMulti: 成功 $downloaded 个文件，已清除 DB 缓存")
                    SyncResult.Success
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "downloadMulti 异常", e)
                SyncResult.Error(e.message ?: "下载异常")
            }
        }

    // ─── 兼容旧版单文件接口 ───────────────────────────────────────

    /** 单文件上传（兼容旧版） */
    suspend fun upload(config: WebDavConfig, dbFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            ensureRemoteFolder(config)
            if (uploadFile(config, "bookkeeper.db", dbFile)) SyncResult.Success
            else SyncResult.Error("上传失败")
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "上传异常")
        }
    }

    /** 单文件下载（兼容旧版） */
    suspend fun download(config: WebDavConfig, destFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            if (downloadFile(config, "bookkeeper.db", destFile)) SyncResult.Success
            else SyncResult.Error("REMOTE_NOT_FOUND")
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "下载异常")
        }
    }

    /** 旧版智能同步（时间戳比对） */
    suspend fun sync(config: WebDavConfig, dbFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            val remoteExists = remoteFileExists(config, "bookkeeper.db")
            if (!remoteExists) return@withContext upload(config, dbFile)
            val remoteTime = getRemoteLastModified(config, "bookkeeper.db") ?: 0L
            val localTime = dbFile.lastModified()
            when {
                localTime > remoteTime + 5000 -> upload(config, dbFile)
                remoteTime > localTime + 5000 -> SyncResult.Conflict(localTime, remoteTime)
                else -> upload(config, dbFile)
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "同步异常")
        }
    }

    /** 删除远程旧版文件（清理迁移残留） */
    suspend fun deleteRemoteLegacyFile(config: WebDavConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val request = Request.Builder()
                .url(remoteFileUrl(config, "bookkeeper.db"))
                .header("Authorization", creds)
                .delete()
                .build()
            val response = client.newCall(request).execute()
            response.code in 200..299 || response.code == 204 || response.code == 404
        } catch (e: Exception) { false }
    }

    companion object {
        private const val TAG = "WebDavManager"
    }
}
