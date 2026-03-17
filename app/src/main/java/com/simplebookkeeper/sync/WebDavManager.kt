package com.simplebookkeeper.sync

import android.content.Context
import com.simplebookkeeper.data.AppDatabase
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
import java.util.concurrent.TimeUnit

sealed class SyncResult {
    object Success : SyncResult()
    data class Conflict(val localTime: Long, val remoteTime: Long) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

class WebDavManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val REMOTE_FOLDER = "bookkeeper"
    private val REMOTE_FILE = "bookkeeper.db"

    // 规范化URL
    private fun baseUrl(config: WebDavConfig): String {
        val url = config.url.trimEnd('/')
        return "$url/$REMOTE_FOLDER"
    }

    private fun remoteFileUrl(config: WebDavConfig): String =
        "${baseUrl(config)}/$REMOTE_FILE"

    // 测试连接
    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "testConnection → url=${config.url}, user=${config.username}")
        try {
            val creds = Credentials.basic(config.username, config.password)
            // 标准 PROPFIND 必须携带完整 XML body
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

    // 确保远端文件夹存在（MKCOL 必须带空 body，不能是 null）
    private suspend fun ensureRemoteFolder(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val creds = Credentials.basic(config.username, config.password)
        val folderUrl = baseUrl(config)
        AppLogger.i(TAG, "ensureRemoteFolder → PROPFIND $folderUrl")
        // 先用 PROPFIND 检查文件夹是否已存在
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
        // 207 = 已存在，无需再创建
        if (checkResp?.code == 207 || checkResp?.code == 200) {
            AppLogger.i(TAG, "ensureRemoteFolder: 远端文件夹已存在，跳过 MKCOL")
            return@withContext
        }

        // 不存在则 MKCOL 创建，body 必须是空字节而非 null
        AppLogger.i(TAG, "ensureRemoteFolder → MKCOL $folderUrl")
        val mkcolReq = Request.Builder()
            .url(folderUrl)
            .header("Authorization", creds)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        val mkcolResp = runCatching { client.newCall(mkcolReq).execute() }.getOrNull()
        AppLogger.i(TAG, "ensureRemoteFolder ← MKCOL HTTP ${mkcolResp?.code}")
        if (mkcolResp?.code != null && mkcolResp.code !in 200..299) {
            AppLogger.w(TAG, "MKCOL 返回非成功码: ${mkcolResp.code}，可能文件夹已存在或权限不足")
        }
    }

    // 上传本地DB到云端（上传前先 checkpoint WAL，确保数据完整）
    suspend fun upload(config: WebDavConfig, dbFile: File): SyncResult = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "upload → 开始上传, dbFile=${dbFile.absolutePath}, size=${dbFile.length()} bytes")
        try {
            ensureRemoteFolder(config)

            // ① WAL checkpoint：把 WAL 日志合并进主 db 文件
            try {
                val db = AppDatabase.getInstance(context)
                db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                AppLogger.i(TAG, "upload: WAL checkpoint 完成，dbFile size=${dbFile.length()} bytes")
            } catch (e: Exception) {
                AppLogger.w(TAG, "WAL checkpoint 失败，继续上传: ${e.message}", e)
            }

            // ② 创建临时副本再上传，避免上传过程中 db 被 Room 修改
            val tempFile = File(context.cacheDir, "upload_tmp.db")
            dbFile.copyTo(tempFile, overwrite = true)
            AppLogger.i(TAG, "upload: 临时副本已创建，size=${tempFile.length()} bytes")

            val targetUrl = remoteFileUrl(config)
            AppLogger.i(TAG, "upload → PUT $targetUrl")
            val creds = Credentials.basic(config.username, config.password)
            val requestBody = tempFile.asRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", creds)
                .put(requestBody)
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""
            AppLogger.i(TAG, "upload ← HTTP ${response.code}, body=${respBody.take(200)}")
            tempFile.delete()

            if (response.code in 200..299) {
                AppLogger.i(TAG, "upload: 上传成功")
                SyncResult.Success
            } else {
                val msg = "上传失败: HTTP ${response.code}"
                AppLogger.e(TAG, "$msg, responseBody=${respBody.take(500)}")
                SyncResult.Error(msg)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "upload 异常", e)
            SyncResult.Error(e.message ?: "上传异常")
        }
    }

    // 从云端下载DB
    suspend fun download(config: WebDavConfig, destFile: File): SyncResult = withContext(Dispatchers.IO) {
        val targetUrl = remoteFileUrl(config)
        AppLogger.i(TAG, "download → GET $targetUrl")
        try {
            val creds = Credentials.basic(config.username, config.password)
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", creds)
                .get()
                .build()
            val response = client.newCall(request).execute()
            AppLogger.i(TAG, "download ← HTTP ${response.code}")
            if (response.code == 200) {
                response.body?.let { body ->
                    destFile.outputStream().use { out ->
                        body.byteStream().copyTo(out)
                    }
                }
                AppLogger.i(TAG, "download: 下载成功，size=${destFile.length()} bytes")
                SyncResult.Success
            } else if (response.code == 404) {
                AppLogger.w(TAG, "download: 远端文件不存在 (404)")
                SyncResult.Error("REMOTE_NOT_FOUND")
            } else {
                val msg = "下载失败: ${response.code}"
                AppLogger.e(TAG, msg)
                SyncResult.Error(msg)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "download 异常", e)
            SyncResult.Error(e.message ?: "下载异常")
        }
    }

    // 检查云端文件是否存在（用 PROPFIND 兼容性更好，HEAD 部分服务器不支持）
    suspend fun remoteFileExists(config: WebDavConfig): Boolean = withContext(Dispatchers.IO) {
        val targetUrl = remoteFileUrl(config)
        AppLogger.i(TAG, "remoteFileExists → PROPFIND $targetUrl")
        try {
            val creds = Credentials.basic(config.username, config.password)
            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><resourcetype/><getlastmodified/></prop></propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", creds)
                .header("Depth", "0")
                .method("PROPFIND", propfindBody)
                .build()
            val response = client.newCall(request).execute()
            val exists = response.code == 207 || response.code == 200
            AppLogger.i(TAG, "remoteFileExists ← HTTP ${response.code}, exists=$exists")
            exists
        } catch (e: Exception) {
            AppLogger.e(TAG, "remoteFileExists 异常", e)
            false
        }
    }

    // 获取云端文件最后修改时间（毫秒），从 PROPFIND 响应解析 getlastmodified
    suspend fun getRemoteLastModified(config: WebDavConfig): Long? = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><getlastmodified/></prop></propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(remoteFileUrl(config))
                .header("Authorization", creds)
                .header("Depth", "0")
                .method("PROPFIND", propfindBody)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            // 从 XML 中提取 <d:getlastmodified> 或 <getlastmodified> 的值
            val regex = Regex("<[^>]*getlastmodified[^>]*>([^<]+)</[^>]*getlastmodified>", RegexOption.IGNORE_CASE)
            val match = regex.find(body)?.groupValues?.get(1) ?: return@withContext null
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(match.trim())?.time
        } catch (e: Exception) {
            AppLogger.e(TAG, "getRemoteLastModified 异常", e)
            null
        }
    }

    // 智能同步：检测冲突，本地优先写入
    suspend fun sync(config: WebDavConfig, dbFile: File): SyncResult = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "sync → 开始智能同步, url=${config.url}")
        try {
            val remoteExists = remoteFileExists(config)
            if (!remoteExists) {
                AppLogger.i(TAG, "sync: 云端无文件，直接上传")
                return@withContext upload(config, dbFile)
            }
            // 比较时间戳
            val remoteTime = getRemoteLastModified(config) ?: 0L
            val localTime = dbFile.lastModified()
            AppLogger.i(TAG, "sync: localTime=$localTime, remoteTime=$remoteTime, diff=${localTime - remoteTime}ms")

            when {
                localTime > remoteTime + 5000 -> {
                    AppLogger.i(TAG, "sync: 本地更新，上传")
                    upload(config, dbFile)
                }
                remoteTime > localTime + 5000 -> {
                    AppLogger.w(TAG, "sync: 云端更新，返回冲突")
                    SyncResult.Conflict(localTime, remoteTime)
                }
                else -> {
                    AppLogger.i(TAG, "sync: 时间基本一致，上传确保同步")
                    upload(config, dbFile)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "sync 异常", e)
            SyncResult.Error(e.message ?: "同步异常")
        }
    }

    companion object {
        private const val TAG = "WebDavManager"
    }
}
