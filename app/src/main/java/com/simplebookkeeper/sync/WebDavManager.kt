package com.simplebookkeeper.sync

import android.content.Context
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

// ─── 同步结果类型 ───────────────────────────────────────────────

sealed class SyncResult {
    object Success : SyncResult()
    data class Conflict(val localTime: Long, val remoteTime: Long) : SyncResult()
    data class MultiConflict(val conflictFiles: List<ConflictFile>) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

data class ConflictFile(
    val fileName: String,
    val localMd5: String,
    val remoteMd5: String
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

                // 同步 meta.db
                if (metaFile.exists()) {
                    val localMd5 = fileMd5(metaFile)
                    val tmp = File(context.cacheDir, "remote_meta.db")
                    if (downloadFile(config, "meta.db", tmp)) {
                        if (localMd5 != fileMd5(tmp)) {
                            conflicts.add(ConflictFile("meta.db", localMd5, fileMd5(tmp)))
                        }
                        tmp.delete()
                    } else if (!uploadFile(config, "meta.db", metaFile)) {
                        return@withContext SyncResult.Error("上传 meta.db 失败")
                    }
                }

                // 同步各年库
                for (yearFile in yearFiles) {
                    val fileName = yearFile.name
                    val localMd5 = fileMd5(yearFile)
                    val tmp = File(context.cacheDir, "remote_$fileName")
                    if (downloadFile(config, fileName, tmp)) {
                        if (localMd5 != fileMd5(tmp)) {
                            conflicts.add(ConflictFile(fileName, localMd5, fileMd5(tmp)))
                        }
                        tmp.delete()
                    } else if (!uploadFile(config, fileName, yearFile)) {
                        return@withContext SyncResult.Error("上传 $fileName 失败")
                    }
                }

                uploadVersionFile(config)

                when {
                    conflicts.isEmpty() -> {
                        AppLogger.i(TAG, "syncMulti: 全部成功")
                        SyncResult.Success
                    }
                    conflicts.size == 1 -> SyncResult.Conflict(metaFile.lastModified(), System.currentTimeMillis())
                    else -> SyncResult.MultiConflict(conflicts)
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
                    // 下载了新的 .db 文件，清除所有 DB 缓存
                    // Room 下次访问时会检测版本并自动执行 MIGRATION_1_2
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
