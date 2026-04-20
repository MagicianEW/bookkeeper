package com.simplebookkeeper.sync

import android.content.Context
import com.simplebookkeeper.data.AppDatabase
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.DatabaseManager
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
import java.util.concurrent.TimeUnit

/**
 * 多文件同步结果
 */
sealed class SyncResult {
    object Success : SyncResult()
    /** 单文件冲突（兼容旧 UI） */
    data class Conflict(val localTime: Long, val remoteTime: Long) : SyncResult()
    /** 多文件冲突列表 */
    data class MultiConflict(val conflictFiles: List<ConflictFile>) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

/** 冲突文件信息 */
data class ConflictFile(
    val fileName: String,     // 如 "2024.db", "meta.db"
    val localMd5: String,
    val remoteMd5: String
)

/**
 * WebDAV 多文件同步管理器
 *
 * 远程目录结构：
 *   bookkeeper/
 *     .version     # 内容 "2"
 *     meta.db      # 分类数据库
 *     2024.db      # 年度交易数据库
 *     2025.db
 *     ...
 */
class WebDavManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val REMOTE_FOLDER = "bookkeeper"
    private val VERSION_FILE = ".version"
    private val CURRENT_VERSION = "2"

    // 规范化URL
    private fun baseUrl(config: WebDavConfig): String {
        val url = config.url.trimEnd('/')
        return "$url/$REMOTE_FOLDER"
    }

    private fun remoteFileUrl(config: WebDavConfig, fileName: String): String =
        "${baseUrl(config)}/$fileName"

    // 测试连接
    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "testConnection → url=${config.url}, user=${config.username}")
        try {
            val creds = Credentials.basic(config.username, config.password)
            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(config.url.trimEnd('/'))
                .header("Authorization", creds)
                .header("Depth", "0")
                .method("PROPFIND", propfindBody)
                .build()
            val response = client.newCall(request).execute()
            AppLogger.i(TAG, "testConnection ← HTTP ${response.code}")
            if (response.code in 200..299 || response.code == 207) {
                Result.success(Unit)
            } else {
                val err = "服务器返回: ${response.code}"
                AppLogger.w(TAG, "testConnection 失败: $err")
                Result.failure(IOException(err))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "testConnection 异常", e)
            Result.failure(e)
        }
    }

    // 确保远端文件夹存在
    private suspend fun ensureRemoteFolder(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val creds = Credentials.basic(config.username, config.password)
        val folderUrl = baseUrl(config)
        AppLogger.i(TAG, "ensureRemoteFolder → PROPFIND $folderUrl")
        val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        val checkReq = Request.Builder()
            .url(folderUrl)
            .header("Authorization", creds)
            .header("Depth", "0")
            .method("PROPFIND", propfindBody)
            .build()
        val checkResp = runCatching { client.newCall(checkReq).execute() }.getOrNull()
        AppLogger.i(TAG, "ensureRemoteFolder ← PROPFIND HTTP ${checkResp?.code}")
        if (checkResp?.code == 207 || checkResp?.code == 200) {
            AppLogger.i(TAG, "ensureRemoteFolder: 远端文件夹已存在")
            return@withContext
        }

        AppLogger.i(TAG, "ensureRemoteFolder → MKCOL $folderUrl")
        val mkcolReq = Request.Builder()
            .url(folderUrl)
            .header("Authorization", creds)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        val mkcolResp = runCatching { client.newCall(mkcolReq).execute() }.getOrNull()
        AppLogger.i(TAG, "ensureRemoteFolder ← MKCOL HTTP ${mkcolResp?.code}")
    }

    // 计算文件 MD5
    private fun fileMd5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var len: Int
            while (input.read(buffer).also { len = it } > 0) {
                md.update(buffer, 0, len)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // 上传单个文件
    private suspend fun uploadFile(config: WebDavConfig, fileName: String, file: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val targetUrl = remoteFileUrl(config, fileName)
                AppLogger.i(TAG, "uploadFile → PUT $targetUrl, size=${file.length()}")
                val creds = Credentials.basic(config.username, config.password)
                val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("Authorization", creds)
                    .put(requestBody)
                    .build()
                val response = client.newCall(request).execute()
                val success = response.code in 200..299
                AppLogger.i(TAG, "uploadFile ← HTTP ${response.code}, success=$success")
                success
            } catch (e: Exception) {
                AppLogger.e(TAG, "uploadFile 异常: $fileName", e)
                false
            }
        }

    // 下载单个文件
    private suspend fun downloadFile(config: WebDavConfig, fileName: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val targetUrl = remoteFileUrl(config, fileName)
                AppLogger.i(TAG, "downloadFile → GET $targetUrl")
                val creds = Credentials.basic(config.username, config.password)
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("Authorization", creds)
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                if (response.code == 200) {
                    response.body?.let { body ->
                        destFile.outputStream().use { out ->
                            body.byteStream().copyTo(out)
                        }
                    }
                    AppLogger.i(TAG, "downloadFile ← size=${destFile.length()}")
                    true
                } else {
                    AppLogger.w(TAG, "downloadFile ← HTTP ${response.code}")
                    false
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "downloadFile 异常: $fileName", e)
                false
            }
        }

    // 获取远程文件列表（PROPFIND）
    suspend fun getRemoteFileList(config: WebDavConfig): List<String> = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><displayname/></prop></propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(baseUrl(config))
                .header("Authorization", creds)
                .header("Depth", "1")
                .method("PROPFIND", propfindBody)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            // 解析 XML 提取文件名
            val regex = Regex("<d:href[^>]*>([^<]+)</d:href>", RegexOption.IGNORE_CASE)
            regex.findAll(body)
                .map { match ->
                    val href = match.groupValues[1]
                    href.substringAfterLast("/").substringAfterLast("%2F").substringAfterLast("%2f")
                }
                .filter { it.isNotBlank() && it != REMOTE_FOLDER }
                .toList()
        } catch (e: Exception) {
            AppLogger.e(TAG, "getRemoteFileList 异常", e)
            emptyList()
        }
    }

    // 检查远程版本文件
    private suspend fun getRemoteVersion(config: WebDavConfig): String? = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val request = Request.Builder()
                .url(remoteFileUrl(config, VERSION_FILE))
                .header("Authorization", creds)
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.code == 200) {
                response.body?.string()?.trim()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // 上传版本文件
    private suspend fun uploadVersionFile(config: WebDavConfig): Boolean =
        withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "version_temp")
            tempFile.writeText(CURRENT_VERSION)
            uploadFile(config, VERSION_FILE, tempFile).also { tempFile.delete() }
        }

    // ——— 多文件同步入口 ———

    /**
     * 多文件同步：上传所有本地 db 文件到云端
     * 逐文件 MD5 比较，有冲突则返回冲突列表
     */
    suspend fun syncMultiFile(config: WebDavConfig): SyncResult = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "syncMultiFile → 开始多文件同步")
        try {
            ensureRemoteFolder(config)

            // 关闭所有数据库连接
            DatabaseManager.closeAll()

            // 获取本地所有 db 文件
            val metaFile = DatabaseManager.getMetaDbFile(context)
            val yearFiles = DatabaseManager.getAllYearDbFiles(context)

            if (!metaFile.exists() && yearFiles.isEmpty()) {
                return@withContext SyncResult.Error("本地无数据")
            }

            // 检查远程版本
            val remoteVersion = getRemoteVersion(config)
            AppLogger.i(TAG, "syncMultiFile: remoteVersion=$remoteVersion")

            // 首次同步或旧版本兼容处理
            if (remoteVersion == null) {
                // 远程无版本文件，检查是否有旧版单库
                val oldRemoteExists = remoteFileExists(config, "bookkeeper.db")
                if (oldRemoteExists) {
                    AppLogger.i(TAG, "syncMultiFile: 检测到旧版远程格式，需要迁移")
                    // 下载旧版单库到本地临时文件
                    val tempOldDb = File(context.cacheDir, "old_remote.db")
                    if (downloadFile(config, "bookkeeper.db", tempOldDb)) {
                        // 迁移旧库
                        val success = com.simplebookkeeper.data.DataExporter.importLegacyDb(context, tempOldDb)
                        tempOldDb.delete()
                        if (!success) {
                            return@withContext SyncResult.Error("旧版数据迁移失败")
                        }
                    }
                }
            }

            // 逐文件上传
            val conflicts = mutableListOf<ConflictFile>()

            // 上传 meta.db
            if (metaFile.exists()) {
                val localMd5 = fileMd5(metaFile)
                // 下载远程 meta.db 计算 MD5
                val tempMeta = File(context.cacheDir, "remote_meta.db")
                if (downloadFile(config, "meta.db", tempMeta)) {
                    val remoteMd5 = fileMd5(tempMeta)
                    if (localMd5 != remoteMd5) {
                        conflicts.add(ConflictFile("meta.db", localMd5, remoteMd5))
                    } else {
                        // MD5 相同，跳过上传
                        AppLogger.i(TAG, "syncMultiFile: meta.db MD5 相同，跳过")
                    }
                    tempMeta.delete()
                } else {
                    // 远程无此文件，直接上传
                    if (!uploadFile(config, "meta.db", metaFile)) {
                        return@withContext SyncResult.Error("上传 meta.db 失败")
                    }
                }
            }

            // 上传各年库
            for (yearFile in yearFiles) {
                val fileName = yearFile.name.removePrefix("bookkeeper_") // 2024.db
                val localMd5 = fileMd5(yearFile)
                val tempYear = File(context.cacheDir, "remote_$fileName")
                if (downloadFile(config, fileName, tempYear)) {
                    val remoteMd5 = fileMd5(tempYear)
                    if (localMd5 != remoteMd5) {
                        conflicts.add(ConflictFile(fileName, localMd5, remoteMd5))
                    } else {
                        AppLogger.i(TAG, "syncMultiFile: $fileName MD5 相同，跳过")
                    }
                    tempYear.delete()
                } else {
                    if (!uploadFile(config, fileName, yearFile)) {
                        return@withContext SyncResult.Error("上传 $fileName 失败")
                    }
                }
            }

            // 上传版本文件
            uploadVersionFile(config)

            if (conflicts.isNotEmpty()) {
                AppLogger.w(TAG, "syncMultiFile: 检测到 ${conflicts.size} 个文件冲突")
                // 兼容旧 UI：如果是单文件冲突，返回旧格式
                if (conflicts.size == 1) {
                    val metaFile = DatabaseManager.getMetaDbFile(context)
                    val localTime = metaFile.lastModified()
                    return@withContext SyncResult.Conflict(localTime, System.currentTimeMillis())
                }
                return@withContext SyncResult.MultiConflict(conflicts)
            }

            AppLogger.i(TAG, "syncMultiFile: 同步成功")
            SyncResult.Success
        } catch (e: Exception) {
            AppLogger.e(TAG, "syncMultiFile 异常", e)
            SyncResult.Error(e.message ?: "同步异常")
        }
    }

    /**
     * 下载所有远程文件到本地
     */
    suspend fun downloadAll(config: WebDavConfig): SyncResult = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "downloadAll → 开始下载")
        try {
            ensureRemoteFolder(config)
            DatabaseManager.closeAll()

            val remoteFiles = getRemoteFileList(config)
            AppLogger.i(TAG, "downloadAll: 远程文件列表 = $remoteFiles")

            var downloaded = 0
            for (fileName in remoteFiles) {
                if (fileName == VERSION_FILE) continue
                if (!fileName.endsWith(".db")) continue

                val destFile = when (fileName) {
                    "meta.db" -> DatabaseManager.getMetaDbFile(context)
                    else -> {
                        val year = fileName.removeSuffix(".db").toIntOrNull() ?: continue
                        DatabaseManager.getYearDbFile(context, year)
                    }
                }

                if (downloadFile(config, fileName, destFile)) {
                    downloaded++
                }
            }

            if (downloaded == 0) {
                SyncResult.Error("远程无数据文件")
            } else {
                AppLogger.i(TAG, "downloadAll: 成功下载 $downloaded 个文件")
                SyncResult.Success
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "downloadAll 异常", e)
            SyncResult.Error(e.message ?: "下载异常")
        }
    }

    // ——— 兼容旧版接口（单文件） ———

    /**
     * 旧版单文件上传（兼容）
     */
    suspend fun upload(config: WebDavConfig, dbFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            ensureRemoteFolder(config)
            val success = uploadFile(config, "bookkeeper.db", dbFile)
            if (success) SyncResult.Success else SyncResult.Error("上传失败")
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "上传异常")
        }
    }

    /**
     * 旧版单文件下载（兼容）
     */
    suspend fun download(config: WebDavConfig, destFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            val success = downloadFile(config, "bookkeeper.db", destFile)
            if (success) SyncResult.Success else SyncResult.Error("REMOTE_NOT_FOUND")
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "下载异常")
        }
    }

    /**
     * 检查远程文件是否存在
     */
    suspend fun remoteFileExists(config: WebDavConfig, fileName: String = "bookkeeper.db"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val creds = Credentials.basic(config.username, config.password)
                val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
                    .toRequestBody("application/xml; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(remoteFileUrl(config, fileName))
                    .header("Authorization", creds)
                    .header("Depth", "0")
                    .method("PROPFIND", propfindBody)
                    .build()
                val response = client.newCall(request).execute()
                response.code == 207 || response.code == 200
            } catch (e: Exception) {
                false
            }
        }

    /**
     * 获取远程文件最后修改时间
     */
    suspend fun getRemoteLastModified(config: WebDavConfig): Long? = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><getlastmodified/></prop></propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(remoteFileUrl(config, "bookkeeper.db"))
                .header("Authorization", creds)
                .header("Depth", "0")
                .method("PROPFIND", propfindBody)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val regex = Regex("<[^>]*getlastmodified[^>]*>([^<]+)</[^>]*getlastmodified>", RegexOption.IGNORE_CASE)
            val match = regex.find(body)?.groupValues?.get(1) ?: return@withContext null
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(match.trim())?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 旧版智能同步（兼容）
     */
    suspend fun sync(config: WebDavConfig, dbFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            val remoteExists = remoteFileExists(config, "bookkeeper.db")
            if (!remoteExists) {
                return@withContext upload(config, dbFile)
            }
            val remoteTime = getRemoteLastModified(config) ?: 0L
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

    /**
     * 删除远程旧版文件（清理）
     */
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
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "WebDavManager"
    }
}
