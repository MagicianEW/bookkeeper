package com.simplebookkeeper.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.SyncResult
import com.simplebookkeeper.sync.SyncWorker
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BookkeeperApp

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    fun syncNow(config: WebDavConfig, password: String? = null, onResult: (SyncResult) -> Unit) {
        viewModelScope.launch {
            try {
                val tempFile = File(app.cacheDir, "sync_export.zip")
                val isPasswordEnabled = app.passwordManager.isPasswordEnabled.first()
                val exportPassword = if (isPasswordEnabled) {
                    val stored = password ?: app.passwordManager.getPlainPassword()
                    if (stored == null) {
                        // 密码存储损坏，无法安全同步，终止操作
                        onResult(SyncResult.Error(app.getString(R.string.sync_password_unavailable)))
                        return@launch
                    }
                    stored
                } else null
                val success = DataExporter.exportToZip(app, tempFile, exportPassword)
                if (success) {
                    val zipBytes = tempFile.readBytes()
                    tempFile.delete()
                    val uploadSuccess = app.webDavManager.uploadData(zipBytes, config)
                    if (uploadSuccess) {
                        onResult(SyncResult.Success)
                    } else {
                        onResult(SyncResult.Error(app.getString(R.string.sync_upload_failed)))
                    }
                } else {
                    tempFile.delete()
                    onResult(SyncResult.Error(app.getString(R.string.sync_export_failed)))
                }
            } catch (e: Exception) {
                AppLogger.e("SyncViewModel", "同步异常", e)
                onResult(SyncResult.Error(e.message ?: app.getString(R.string.sync_error)))
            }
        }
    }

    fun downloadFromCloud(config: WebDavConfig, password: String? = null, onResult: (SyncResult) -> Unit) {
        viewModelScope.launch {
            try {
                val zipBytes = app.webDavManager.downloadData(config)
                if (zipBytes == null) {
                    onResult(SyncResult.Error(app.getString(R.string.sync_remote_not_found)))
                    return@launch
                }
                val tempFile = File(app.cacheDir, "sync_import.zip")
                tempFile.writeBytes(zipBytes)
                // 与 syncNow 保持一致：未显式传密码时回落到安全存储中的应用锁密码，
                // 否则开启应用锁时导出的加密 ZIP 会因缺密码而无法解密
                val isPasswordEnabled = app.passwordManager.isPasswordEnabled.first()
                val importPassword = if (isPasswordEnabled) {
                    password ?: app.passwordManager.getPlainPassword()
                } else null
                val success = DataExporter.importFromZip(app, tempFile, importPassword)
                tempFile.delete()
                if (success) {
                    onResult(SyncResult.Success)
                } else {
                    onResult(SyncResult.Error(app.getString(R.string.sync_import_failed)))
                }
            } catch (e: Exception) {
                AppLogger.e("SyncViewModel", "下载异常", e)
                onResult(SyncResult.Error(e.message ?: app.getString(R.string.sync_download_failed)))
            }
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = null
    }
}
