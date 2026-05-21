package com.simplebookkeeper.sync

import android.content.Context
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ─── 同步结果类型 ───────────────────────────────────────────────

sealed class SyncResult {
    object Success : SyncResult()
    data class Error(val message: String) : SyncResult()
}

// ─── WebDAV 管理器 ───────────────────────────────────────────────

class WebDavManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val REMOTE_FOLDER = "bookkeeper"
    private val ZIP_FILE_NAME = "bookkeeper_data.zip"

    private fun baseUrl(config: WebDavConfig): String =
        config.url.trimEnd('/') + "/$REMOTE_FOLDER"

    private fun remoteFileUrl(config: WebDavConfig, fileName: String): String =
        "${baseUrl(config)}/$fileName"

    // ─── 公开 API ────────────────────────────────────────────────

    /**
     * 上传 ZIP 数据到 WebDAV
     * 上传前将远程旧文件重命名为 bookkeeper_data_YYYY-MM-DD.zip，保留最近 5 个版本
     */
    suspend fun uploadData(zipBytes: ByteArray, config: WebDavConfig): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureRemoteFolder(config)

                // 备份远程旧文件
                if (remoteFileExists(config, ZIP_FILE_NAME)) {
                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val backupName = "bookkeeper_data_$dateStr.zip"
                    AppLogger.i(TAG, "uploadData: 备份 $ZIP_FILE_NAME → $backupName")
                    if (!moveRemoteFile(config, ZIP_FILE_NAME, backupName)) {
                        AppLogger.w(TAG, "uploadData: 备份失败，继续上传（可能已有同名备份）")
                    }
                    cleanupOldBackups(config)
                }

                // 上传新文件
                val tempFile = File(context.cacheDir, "upload_temp.zip")
                tempFile.writeBytes(zipBytes)
                val success = uploadFile(config, ZIP_FILE_NAME, tempFile)
                tempFile.delete()

                if (success) {
                    AppLogger.i(TAG, "uploadData: 上传成功 ${zipBytes.size} bytes")
                } else {
                    AppLogger.e(TAG, "uploadData: 上传失败")
                }
                success
            } catch (e: Exception) {
                AppLogger.e(TAG, "uploadData 异常", e)
                false
            }
        }

    /**
     * 从 WebDAV 下载最新的 ZIP 数据
     */
    suspend fun downloadData(config: WebDavConfig): ByteArray? = withContext(Dispatchers.IO) {
        try {
            ensureRemoteFolder(config)

            if (!remoteFileExists(config, ZIP_FILE_NAME)) {
                AppLogger.i(TAG, "downloadData: 远程无数据文件")
                return@withContext null
            }

            val tempFile = File(context.cacheDir, "download_temp.zip")
            val success = downloadFile(config, ZIP_FILE_NAME, tempFile)
            if (success && tempFile.exists()) {
                val bytes = tempFile.readBytes()
                tempFile.delete()
                AppLogger.i(TAG, "downloadData: 下载成功 ${bytes.size} bytes")
                bytes
            } else {
                tempFile.delete()
                AppLogger.e(TAG, "downloadData: 下载失败")
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "downloadData 异常", e)
            null
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
            val request = okhttp3.Request.Builder()
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

    /** 检查远程是否有数据文件 */
    suspend fun hasRemoteData(config: WebDavConfig): Boolean = withContext(Dispatchers.IO) {
        ensureRemoteFolder(config)
        remoteFileExists(config, ZIP_FILE_NAME)
    }

    // ─── 远程文件操作 ─────────────────────────────────────────────

    private suspend fun ensureRemoteFolder(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val creds = Credentials.basic(config.username, config.password)
        val folderUrl = baseUrl(config)

        val checkReq = okhttp3.Request.Builder()
            .url(folderUrl)
            .header("Authorization", creds)
            .header("Depth", "0")
            .method("PROPFIND", """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
                .toRequestBody("application/xml".toMediaType()))
            .build()
        val checkResp = runCatching { client.newCall(checkReq).execute() }.getOrNull()

        if (checkResp?.code == 207 || checkResp?.code == 200) return@withContext

        AppLogger.i(TAG, "ensureRemoteFolder → MKCOL $folderUrl")
        val mkcolReq = okhttp3.Request.Builder()
            .url(folderUrl)
            .header("Authorization", creds)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        runCatching { client.newCall(mkcolReq).execute() }
    }

    /** MOVE 远程文件（rename） */
    private suspend fun moveRemoteFile(
        config: WebDavConfig,
        srcName: String,
        destName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val request = okhttp3.Request.Builder()
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

    /** 清理旧备份，只保留最近 5 份 */
    private suspend fun cleanupOldBackups(config: WebDavConfig) = withContext(Dispatchers.IO) {
        try {
            val datePattern = Regex("""bookkeeper_data_(\d{4}-\d{2}-\d{2})\.zip""")
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
                val request = okhttp3.Request.Builder()
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

    private suspend fun uploadFile(config: WebDavConfig, fileName: String, file: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val creds = Credentials.basic(config.username, config.password)
                val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
                val request = okhttp3.Request.Builder()
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
                val request = okhttp3.Request.Builder()
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
                val request = okhttp3.Request.Builder()
                    .url(remoteFileUrl(config, fileName))
                    .header("Authorization", creds)
                    .header("Depth", "0")
                    .method("PROPFIND", body)
                    .build()
                val response = client.newCall(request).execute()
                response.code == 207 || response.code == 200
            } catch (e: Exception) { false }
        }

    suspend fun getRemoteFileList(config: WebDavConfig): List<String> = withContext(Dispatchers.IO) {
        try {
            val creds = Credentials.basic(config.username, config.password)
            val body = """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><displayname/></prop></propfind>"""
                .toRequestBody("application/xml".toMediaType())
            val request = okhttp3.Request.Builder()
                .url(baseUrl(config))
                .header("Authorization", creds)
                .header("Depth", "1")
                .method("PROPFIND", body)
                .build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext emptyList()
            val regex = Regex("<[a-zA-Z0-9]*:??href[^>]*>([^<]+)</[a-zA-Z0-9]*:??href>", RegexOption.IGNORE_CASE)
            regex.findAll(html)
                .map { it.groupValues[1].substringAfterLast("/").substringAfterLast("%2F").substringAfterLast("%2f") }
                .filter { it.isNotBlank() && it != REMOTE_FOLDER }
                .toList()
        } catch (e: Exception) {
            AppLogger.e(TAG, "getRemoteFileList 异常", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "WebDavManager"
    }
}
