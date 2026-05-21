package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.SyncResult
import com.simplebookkeeper.sync.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 首次启动弹出的云端同步引导对话框
 *
 * 流程：
 * 1. 询问是否从云端恢复
 * 2. 输入 WebDAV 配置
 * 3. 检查云端数据是否加密
 * 4. 已加密 + 本地有密码 → 用密码解密
 * 5. 已加密 + 本地无密码 → 提示设置密码或跳过
 * 6. 未加密 → 直接下载导入
 */
@Composable
fun FirstLaunchSyncDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as BookkeeperApp
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) }
    // 0=询问, 1=输入配置, 2=检查中/下载中, 3=需要密码, 4=结果
    var webDavUrl by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }
    var zipPassword by remember { mutableStateOf("") }
    var needPassword by remember { mutableStateOf(false) }
    var tempZipFile by remember { mutableStateOf<File?>(null) }

    // 预提取 onClick 等非 Composable 作用域中需要的字符串
    val connectionFailedTemplate = stringResource(R.string.connection_failed)
    val noCloudDataText = stringResource(R.string.no_cloud_data)
    val restoreSuccessMessage = stringResource(R.string.restore_success_message)
    val importFailedFormatText = stringResource(R.string.import_failed_format)
    val decryptFailedText = stringResource(R.string.decrypt_failed)
    val tempFileMissingText = stringResource(R.string.temp_file_missing)

    AlertDialog(
        onDismissRequest = {
            if (step != 2) {
                scope.launch { app.settingsRepository.markCloudSyncPromptShown() }
                tempZipFile?.delete()
                onDismiss()
            }
        },
        icon = { Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(40.dp)) },
        title = {
            Text(
                when (step) {
                    0 -> stringResource(R.string.cloud_restore_question)
                    1 -> stringResource(R.string.input_webdav)
                    2 -> stringResource(R.string.processing)
                    3 -> stringResource(R.string.input_decrypt_password)
                    else -> if (isSuccess) stringResource(R.string.restore_success) else stringResource(R.string.restore_failed)
                },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            when (step) {
                0 -> Text(
                    stringResource(R.string.cloud_restore_message),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                1 -> Column {
                    OutlinedTextField(
                        value = webDavUrl,
                        onValueChange = { webDavUrl = it },
                        label = { Text(stringResource(R.string.webdav_address)) },
                        placeholder = { Text(stringResource(R.string.webdav_address_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webDavUsername,
                        onValueChange = { webDavUsername = it },
                        label = { Text(stringResource(R.string.webdav_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webDavPassword,
                        onValueChange = { webDavPassword = it },
                        label = { Text(stringResource(R.string.webdav_password)) },
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) stringResource(R.string.hide) else stringResource(R.string.show), fontSize = 12.sp)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                2 -> Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.downloading_data))
                    }
                }
                3 -> Column {
                    Text(
                        stringResource(R.string.cloud_data_encrypted),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = zipPassword,
                        onValueChange = { zipPassword = it },
                        label = { Text(stringResource(R.string.decrypt_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> Text(
                    resultMessage,
                    textAlign = TextAlign.Center,
                    color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            when (step) {
                0 -> TextButton(onClick = { step = 1 }) { Text(stringResource(R.string.yes_restore)) }
                1 -> TextButton(
                    onClick = {
                        if (webDavUrl.isBlank() || webDavUsername.isBlank()) return@TextButton
                        step = 2
                        scope.launch {
                            val config = WebDavConfig(webDavUrl.trim(), webDavUsername.trim(), webDavPassword, true)

                            // 先测试连接
                            val testResult = app.webDavManager.testConnection(config)
                            if (testResult.isFailure) {
                                isSuccess = false
                                resultMessage = connectionFailedTemplate.format(testResult.exceptionOrNull()?.message)
                                step = 4
                                return@launch
                            }

                            // 下载数据
                            val zipBytes = app.webDavManager.downloadData(config)
                            if (zipBytes == null) {
                                isSuccess = false
                                resultMessage = noCloudDataText
                                step = 4
                                return@launch
                            }

                            // 保存临时文件
                            val temp = File(context.cacheDir, "first_launch_import.zip")
                            temp.writeBytes(zipBytes)
                            tempZipFile = temp

                            // 检查是否加密
                            val meta = DataExporter.readMetaFromZip(temp)
                            val isEncrypted = meta?.optBoolean("encrypted", false) ?: false

                            if (isEncrypted) {
                                val hasLocalPassword = app.passwordManager.isPasswordEnabled.first()
                                if (hasLocalPassword) {
                                    // 尝试用本地密码解密
                                    // 但我们不知道用户的密码明文，需要让用户输入
                                    needPassword = true
                                    step = 3
                                } else {
                                    // 本地无密码，提示需要设置密码
                                    needPassword = true
                                    step = 3
                                }
                            } else {
                                // 未加密，直接导入
                                val importSuccess = DataExporter.importFromZip(context, temp, null)
                                temp.delete()
                                tempZipFile = null

                                if (importSuccess) {
                                    app.settingsRepository.saveWebDavConfig(config)
                                    SyncWorker.schedule(context)
                                    isSuccess = true
                                    resultMessage = restoreSuccessMessage
                                } else {
                                    isSuccess = false
                                    resultMessage = importFailedFormatText
                                }
                                app.settingsRepository.markCloudSyncPromptShown()
                                step = 4
                            }
                        }
                    },
                    enabled = webDavUrl.isNotBlank() && webDavUsername.isNotBlank()
                ) { Text(stringResource(R.string.start_restore)) }
                3 -> TextButton(
                    onClick = {
                        step = 2
                        scope.launch {
                            val temp = tempZipFile
                            if (temp != null && temp.exists()) {
                                val importSuccess = DataExporter.importFromZip(context, temp, zipPassword.ifBlank { null })
                                temp.delete()
                                tempZipFile = null

                                if (importSuccess) {
                                    val config = WebDavConfig(webDavUrl.trim(), webDavUsername.trim(), webDavPassword, true)
                                    app.settingsRepository.saveWebDavConfig(config)
                                    SyncWorker.schedule(context)
                                    isSuccess = true
                                    resultMessage = restoreSuccessMessage
                                } else {
                                    isSuccess = false
                                    resultMessage = decryptFailedText
                                }
                            } else {
                                isSuccess = false
                                resultMessage = tempFileMissingText
                            }
                            app.settingsRepository.markCloudSyncPromptShown()
                            step = 4
                        }
                    },
                    enabled = zipPassword.isNotBlank()
                ) { Text(stringResource(R.string.decrypt_and_restore)) }
                4 -> TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.confirm)) }
                else -> {}
            }
        },
        dismissButton = {
            when (step) {
                0 -> TextButton(onClick = {
                    scope.launch { app.settingsRepository.markCloudSyncPromptShown() }
                    onDismiss()
                }) { Text(stringResource(R.string.no_start_fresh)) }
                1 -> TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.back_action)) }
                3 -> TextButton(onClick = {
                    // 跳过密码，不进行恢复
                    tempZipFile?.delete()
                    scope.launch { app.settingsRepository.markCloudSyncPromptShown() }
                    onDismiss()
                }) { Text(stringResource(R.string.skip_action)) }
                else -> {}
            }
        }
    )
}
