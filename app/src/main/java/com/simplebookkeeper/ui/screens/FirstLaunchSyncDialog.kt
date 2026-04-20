package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.data.AppDatabase
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.SyncResult
import com.simplebookkeeper.sync.SyncWorker
import kotlinx.coroutines.launch

/**
 * 首次启动弹出的云端同步引导对话框
 * 询问用户是否从 WebDAV 恢复之前的数据
 */
@Composable
fun FirstLaunchSyncDialog(
    onDismiss: () -> Unit  // 无论选择什么，最后都调用 onDismiss 进入主界面
) {
    val context = LocalContext.current
    val app = context.applicationContext as BookkeeperApp
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) } // 0=询问, 1=输入配置, 2=同步中, 3=结果
    var webDavUrl by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (step != 2) {
                scope.launch { app.settingsRepository.markCloudSyncPromptShown() }
                onDismiss()
            }
        },
        icon = { Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(40.dp)) },
        title = {
            Text(
                when (step) {
                    0 -> "从云端恢复数据？"
                    1 -> "输入 WebDAV 配置"
                    2 -> "正在同步..."
                    else -> if (isSuccess) "恢复成功" else "恢复失败"
                },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            when (step) {
                0 -> Text(
                    "检测到这是首次启动。\n如果您之前在其他设备上使用过简单记账，并已配置 WebDAV 云端备份，可以立即从云端恢复数据。\n\n是否从云端恢复？",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                1 -> Column {
                    OutlinedTextField(
                        value = webDavUrl,
                        onValueChange = { webDavUrl = it },
                        label = { Text("WebDAV 地址") },
                        placeholder = { Text("https://your-server/dav") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webDavUsername,
                        onValueChange = { webDavUsername = it },
                        label = { Text("用户名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webDavPassword,
                        onValueChange = { webDavPassword = it },
                        label = { Text("密码") },
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) "隐藏" else "显示", fontSize = 12.sp)
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
                        Text("正在从云端下载数据，请稍候...")
                    }
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
                0 -> TextButton(onClick = { step = 1 }) { Text("是，从云端恢复") }
                1 -> TextButton(
                    onClick = {
                        if (webDavUrl.isBlank() || webDavUsername.isBlank()) return@TextButton
                        step = 2
                        scope.launch {
                            val config = WebDavConfig(webDavUrl.trim(), webDavUsername.trim(), webDavPassword, true)
                            val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
                            val result = app.webDavManager.download(config, dbFile)
                            when (result) {
                                is SyncResult.Success -> {
                                    // 保存配置并启动后台同步
                                    app.settingsRepository.saveWebDavConfig(config)
                                    SyncWorker.schedule(context)
                                    isSuccess = true
                                    resultMessage = "数据恢复成功！\n请重启应用以加载恢复的数据。"
                                }
                                is SyncResult.Error -> {
                                    isSuccess = false
                                    resultMessage = if (result.message == "REMOTE_NOT_FOUND") {
                                        "云端暂无数据文件，将以本地数据为准继续使用。"
                                    } else {
                                        "恢复失败：${result.message}"
                                    }
                                }
                                is SyncResult.Conflict -> {
                                    isSuccess = true
                                    resultMessage = "数据已下载，请重启应用。"
                                }
                                is SyncResult.MultiConflict -> {
                                    isSuccess = true
                                    resultMessage = "数据已下载（部分文件冲突），请重启应用。"
                                }
                            }
                            app.settingsRepository.markCloudSyncPromptShown()
                            step = 3
                        }
                    },
                    enabled = webDavUrl.isNotBlank() && webDavUsername.isNotBlank()
                ) { Text("开始恢复") }
                3 -> TextButton(onClick = { onDismiss() }) { Text("确定") }
                else -> {}
            }
        },
        dismissButton = {
            when (step) {
                0 -> TextButton(onClick = {
                    scope.launch { app.settingsRepository.markCloudSyncPromptShown() }
                    onDismiss()
                }) { Text("否，直接进入") }
                1 -> TextButton(onClick = { step = 0 }) { Text("返回") }
                else -> {}
            }
        }
    )
}
