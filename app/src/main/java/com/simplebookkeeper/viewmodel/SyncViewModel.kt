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
                val exportPassword = if (app.passwordManager.isPasswordEnabled.first()) {
                    password ?: app.passwordManager.getPlainPassword()
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
                    onResult(SyncResult.Error("REMOTE_NOT_FOUND"))
                    return@launch
                }
                val tempFile = File(app.cacheDir, "sync_import.zip")
                tempFile.writeBytes(zipBytes)
                val success = DataExporter.importFromZip(app, tempFile, password)
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
