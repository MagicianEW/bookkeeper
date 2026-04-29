package com.simplebookkeeper.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.data.AppDatabase
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.DatabaseManager
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.BackupVersion
import com.simplebookkeeper.sync.ConflictFile
import com.simplebookkeeper.sync.SyncResult
import com.simplebookkeeper.sync.SyncWorker
import com.simplebookkeeper.util.AppLogger
import com.simplebookkeeper.viewmodel.MainViewModel
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val app = context.applicationContext as BookkeeperApp
    val scope = rememberCoroutineScope()

    // 密码相关
    val isPasswordEnabled by app.passwordManager.isPasswordEnabled.collectAsState(initial = false)
    val isBiometricEnabled by app.passwordManager.isBiometricEnabled.collectAsState(initial = false)
    val canUseBiometric = app.biometricAuth.canAuthenticate()

    // WebDAV配置
    val webDavConfig by app.settingsRepository.webDavConfig.collectAsState(initial = WebDavConfig())
    var webDavUrl by remember(webDavConfig.url) { mutableStateOf(webDavConfig.url) }
    var webDavUsername by remember(webDavConfig.username) { mutableStateOf(webDavConfig.username) }
    var webDavPassword by remember(webDavConfig.password) { mutableStateOf(webDavConfig.password) }
    var webDavEnabled by remember(webDavConfig.enabled) { mutableStateOf(webDavConfig.enabled) }

    // 对话框状态
    var showSetPasswordDialog by remember { mutableStateOf(false) }
    var showDisablePasswordDialog by remember { mutableStateOf(false) }
    var showPasswordVerifyForDisable by remember { mutableStateOf(false) }  // 关闭生物识别时密码验证
    var showWebDavSection by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var showPasswordVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    // 冲突解决对话框状态
    var showConflictDialog by remember { mutableStateOf(false) }
    var conflictData by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var conflictConfig by remember { mutableStateOf<WebDavConfig?>(null) }
    var multiConflictFiles by remember { mutableStateOf<List<ConflictFile>>(emptyList()) }

    // 备份版本选择对话框状态
    var showBackupDialog by remember { mutableStateOf(false) }
    var backupVersions by remember { mutableStateOf<List<BackupVersion>>(emptyList()) }
    var backupConfig by remember { mutableStateOf<WebDavConfig?>(null) }

    val allCategories by viewModel.allCategories.collectAsState()

    // 导出文件选择器（导出为 zip）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch {
                val tempFile = File(context.cacheDir, "bookkeeper_export.zip")
                val success = DataExporter.exportToZip(context, tempFile)
                if (success) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                    syncMessage = "✅ 导出成功"
                } else {
                    syncMessage = "❌ 导出失败"
                }
                tempFile.delete()
            }
        }
    }

    // 导出日志文件选择器
    val exportLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            scope.launch {
                exportLogsToUri(context, it)
                syncMessage = "日志导出成功"
            }
        }
    }

    // 导入文件选择器（支持 zip 和 db）
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val success = importData(context, it)
                syncMessage = if (success) "✅ 导入成功，请重启应用以完成数据迁移" else "❌ 导入失败，文件格式不正确"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showAboutDialog = true }) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "关于",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ——— 安全设置 ———
            item {
                SettingsSectionHeader("安全")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Lock,
                    title = if (isPasswordEnabled) "修改密码" else "设置密码",
                    subtitle = if (isPasswordEnabled) "已设置密码保护" else "设置后下次启动需要验证",
                    onClick = { showSetPasswordDialog = true }
                )
            }
            if (isPasswordEnabled) {
                item {
                    SettingsItem(
                        icon = Icons.Default.LockOpen,
                        title = "关闭密码",
                        subtitle = "关闭后启动无需验证",
                        onClick = { showDisablePasswordDialog = true }
                    )
                }
                if (canUseBiometric) {
                    item {
                        SettingsSwitchItem(
                            icon = Icons.Default.Fingerprint,
                            title = "生物识别",
                            subtitle = "使用指纹或面容解锁",
                            checked = isBiometricEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    // 开启时先验证生物识别
                                    val activity = context as? FragmentActivity
                                    if (activity != null) {
                                        app.biometricAuth.authenticate(
                                            activity = activity,
                                            onSuccess = {
                                                scope.launch { app.passwordManager.setBiometricEnabled(true) }
                                            },
                                            onFailed = { },
                                            onError = { }
                                        )
                                    }
                                } else {
                                    // 关闭时也要验证：优先生物识别，失败则密码验证
                                    val activity = context as? FragmentActivity
                                    if (activity != null) {
                                        app.biometricAuth.authenticate(
                                            activity = activity,
                                            onSuccess = {
                                                scope.launch { app.passwordManager.setBiometricEnabled(false) }
                                            },
                                            onFailed = { /* 生物识别失败，尝试密码 */
                                                showPasswordVerifyForDisable = true
                                            },
                                            onError = { }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // ——— 云端同步 ———
            item { SettingsSectionHeader("云端同步") }
            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Cloud,
                    title = "WebDAV 同步",
                    subtitle = if (webDavEnabled && webDavUrl.isNotBlank()) "已配置: ${webDavUrl.take(30)}" else "未配置",
                    checked = webDavEnabled,
                    onCheckedChange = { enabled ->
                        webDavEnabled = enabled
                        scope.launch {
                            app.settingsRepository.saveWebDavConfig(
                                webDavConfig.copy(enabled = enabled)
                            )
                            if (enabled && webDavUrl.isNotBlank()) {
                                SyncWorker.schedule(context)
                            } else {
                                SyncWorker.cancel(context)
                            }
                        }
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Settings,
                    title = "配置 WebDAV 服务器",
                    subtitle = "设置服务器地址和账号",
                    onClick = { showWebDavSection = !showWebDavSection }
                )
            }
            if (showWebDavSection) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
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
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showPasswordVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPasswordVisible = !showPasswordVisible }) {
                                        Icon(
                                            if (showPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        isTesting = true
                                        scope.launch {
                                            val testConfig = WebDavConfig(webDavUrl, webDavUsername, webDavPassword, true)
                                            val result = app.webDavManager.testConnection(testConfig)
                                            syncMessage = if (result.isSuccess) "连接成功！" else "连接失败: ${result.exceptionOrNull()?.message}"
                                            isTesting = false
                                        }
                                    },
                                    enabled = !isTesting && webDavUrl.isNotBlank()
                                ) {
                                    if (isTesting) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    else Text("测试连接")
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            app.settingsRepository.saveWebDavConfig(
                                                WebDavConfig(webDavUrl, webDavUsername, webDavPassword, webDavEnabled)
                                            )
                                            syncMessage = "配置已保存"
                                            showWebDavSection = false
                                        }
                                    }
                                ) { Text("保存配置") }
                            }
                        }
                    }
                }
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Sync,
                    title = if (isSyncing) "同步中..." else "立即同步",
                    subtitle = if (isSyncing) "正在同步数据，请稍候" else "将本地数据同步到云端",
                    onClick = {
                        if (isSyncing) return@SettingsItem
                        scope.launch {
                            val config = app.settingsRepository.webDavConfig.first()
                            if (!config.enabled || config.url.isBlank()) {
                                syncMessage = "请先配置并启用 WebDAV"
                                return@launch
                            }
                            isSyncing = true
                            syncMessage = null
                            when (val result = app.webDavManager.syncMulti(config, app.dbManager)) {
                                is SyncResult.Success -> syncMessage = "✅ 同步成功"
                                is SyncResult.Error -> syncMessage = "❌ 同步失败: ${result.message}"
                                is SyncResult.Conflict -> {
                                    conflictData = result.localTime to result.remoteTime
                                    conflictConfig = config
                                    multiConflictFiles = emptyList()
                                    showConflictDialog = true
                                }
                                is SyncResult.MultiConflict -> {
                                    conflictConfig = config
                                    multiConflictFiles = result.conflictFiles
                                    conflictData = null
                                    showConflictDialog = true
                                }
                                is SyncResult.SizeMismatch -> {
                                    conflictConfig = config
                                    multiConflictFiles = result.conflictFiles
                                    conflictData = null
                                    showConflictDialog = true
                                }
                                else -> {}
                            }
                            isSyncing = false
                        }
                    }
                )
            }
            // 从备份恢复
            item {
                SettingsItem(
                    icon = Icons.Default.History,
                    title = "从备份恢复",
                    subtitle = "选择云端历史版本恢复数据",
                    onClick = {
                        if (isSyncing) return@SettingsItem
                        scope.launch {
                            val config = app.settingsRepository.webDavConfig.first()
                            if (!config.enabled || config.url.isBlank()) {
                                syncMessage = "请先配置并启用 WebDAV"
                                return@launch
                            }
                            isSyncing = true
                            syncMessage = "正在获取备份列表..."
                            val backups = app.webDavManager.getRemoteBackups(config)
                            isSyncing = false
                            if (backups.isEmpty()) {
                                syncMessage = "❌ 未找到云端备份（请先同步一次）"
                            } else {
                                backupVersions = backups
                                backupConfig = config
                                showBackupDialog = true
                                syncMessage = null
                            }
                        }
                    }
                )
            }
            // 清理旧版云端文件
            item {
                SettingsItem(
                    icon = Icons.Default.CleaningServices,
                    title = "清理旧版云端格式",
                    subtitle = "删除云端旧版 bookkeeper.db（已迁移数据不受影响）",
                    onClick = {
                        scope.launch {
                            val config = app.settingsRepository.webDavConfig.first()
                            if (!config.enabled || config.url.isBlank()) {
                                syncMessage = "请先配置 WebDAV"
                                return@launch
                            }
                            val success = app.webDavManager.deleteRemoteLegacyFile(config)
                            syncMessage = if (success) "✅ 旧版云端文件已清理" else "❌ 清理失败或文件不存在"
                        }
                    }
                )
            }

            // ——— 数据管理 ———
            item { SettingsSectionHeader("数据管理") }
            item {
                SettingsItem(
                    icon = Icons.Default.Upload,
                    title = "导出数据",
                    subtitle = "将所有数据库导出为 .zip 文件",
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLauncher.launch("bookkeeper_$timestamp.zip")
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Download,
                    title = "导入数据",
                    subtitle = "从 .zip 或旧版 .db 文件恢复数据",
                    onClick = { importLauncher.launch("*/*") }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Description,
                    title = "导出日志",
                    subtitle = "将运行日志导出为文本文件供分析",
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLogLauncher.launch("bookkeeper_log_$timestamp.txt")
                    }
                )
            }

            // ——— 分类管理 ———
            item { SettingsSectionHeader("分类管理") }
            item {
                SettingsItem(
                    icon = Icons.Default.Add,
                    title = "添加自定义分类",
                    subtitle = "新增收入或支出分类",
                    onClick = { showAddCategoryDialog = true }
                )
            }
            items(allCategories.filter { !it.isDefault }) { category ->
                CategorySettingsItem(
                    category = category,
                    onDelete = { viewModel.deleteCategory(category) }
                )
            }
        }
    }

    // 显示消息
    syncMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(5000)
            syncMessage = null
        }
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = { syncMessage = null }) { Text("关闭") }
            }
        ) { Text(msg) }
    }

    // 设置密码对话框
    if (showSetPasswordDialog) {
        SetPasswordDialog(
            onConfirm = { newPassword ->
                scope.launch {
                    app.passwordManager.setPassword(newPassword)
                    syncMessage = "密码已设置"
                }
                showSetPasswordDialog = false
            },
            onDismiss = { showSetPasswordDialog = false }
        )
    }

    // 关闭密码确认
    if (showDisablePasswordDialog) {
        AlertDialog(
            onDismissRequest = { showDisablePasswordDialog = false },
            title = { Text("关闭密码保护") },
            text = { Text("关闭后应用将不再需要密码验证，确定要关闭吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.passwordManager.disablePassword() }
                    showDisablePasswordDialog = false
                }) { Text("确定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDisablePasswordDialog = false }) { Text("取消") }
            }
        )
    }

    // 关闭生物识别时：密码验证对话框
    if (showPasswordVerifyForDisable) {
        var passwordInput by remember { mutableStateOf("") }
        var verifyError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showPasswordVerifyForDisable = false },
            title = { Text("验证密码") },
            text = {
                Column {
                    Text("请输入密码以关闭生物识别", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it; verifyError = null },
                        label = { Text("密码") },
                        singleLine = true,
                        isError = verifyError != null,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                scope.launch {
                                    val ok = app.passwordManager.verifyPassword(passwordInput)
                                    if (ok) {
                                        app.passwordManager.setBiometricEnabled(false)
                                        showPasswordVerifyForDisable = false
                                    } else {
                                        verifyError = "密码错误"
                                    }
                                }
                            }
                        )
                    )
                    verifyError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = app.passwordManager.verifyPassword(passwordInput)
                        if (ok) {
                            app.passwordManager.setBiometricEnabled(false)
                            showPasswordVerifyForDisable = false
                        } else {
                            verifyError = "密码错误"
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordVerifyForDisable = false }) { Text("取消") }
            }
        )
    }

    // 添加分类对话框
    if (showAddCategoryDialog) {
        AddCategoryDialog(
            onConfirm = { category ->
                viewModel.addCategory(category)
                showAddCategoryDialog = false
            },
            onDismiss = { showAddCategoryDialog = false }
        )
    }

    // 关于对话框
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    // 冲突解决对话框
    if (showConflictDialog) {
        if (multiConflictFiles.isNotEmpty()) {
            // 多文件冲突
            MultiConflictResolveDialog(
                conflictFiles = multiConflictFiles,
                onResolve = { useLocal ->
                    scope.launch {
                        val cfg = conflictConfig ?: return@launch
                        if (useLocal) {
                            when (val result = app.webDavManager.syncMulti(cfg, app.dbManager, forceOverwrite = true)) {
                                is SyncResult.Success, is SyncResult.SizeMismatch -> syncMessage = "✅ 已以本地数据为准同步到云端"
                                is SyncResult.Error -> syncMessage = "❌ 同步失败: ${result.message}"
                                else -> {}
                            }
                        } else {
                            when (val result = app.webDavManager.downloadMulti(cfg, app.dbManager)) {
                                is SyncResult.Success -> syncMessage = "✅ 已以云端数据为准覆盖本地"
                                is SyncResult.Error -> syncMessage = "❌ 下载失败: ${result.message}"
                                else -> {}
                            }
                        }
                    }
                    showConflictDialog = false
                    conflictData = null
                    conflictConfig = null
                    multiConflictFiles = emptyList()
                },
                onDismiss = {
                    showConflictDialog = false
                    conflictData = null
                    conflictConfig = null
                    multiConflictFiles = emptyList()
                }
            )
        } else if (conflictData != null) {
            // 单文件冲突（兼容旧 UI）
            val (localTime, remoteTime) = conflictData!!
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            ConflictResolveDialog(
                localTimeStr = dateFormat.format(Date(localTime)),
                remoteTimeStr = dateFormat.format(Date(remoteTime)),
                onResolve = { useLocal ->
                    scope.launch {
                        val cfg = conflictConfig ?: return@launch
                        if (useLocal) {
                            when (val result = app.webDavManager.syncMulti(cfg, app.dbManager, forceOverwrite = true)) {
                                is SyncResult.Success, is SyncResult.SizeMismatch -> syncMessage = "✅ 已以本地数据为准覆盖云端"
                                is SyncResult.Error -> syncMessage = "❌ 上传失败: ${result.message}"
                                else -> {}
                            }
                        } else {
                            when (val result = app.webDavManager.downloadMulti(cfg, app.dbManager)) {
                                is SyncResult.Success -> syncMessage = "✅ 已以云端数据为准覆盖本地"
                                is SyncResult.Error -> syncMessage = "❌ 下载失败: ${result.message}"
                                else -> {}
                            }
                        }
                    }
                    showConflictDialog = false
                    conflictData = null
                    conflictConfig = null
                },
                onDismiss = {
                    showConflictDialog = false
                    conflictData = null
                    conflictConfig = null
                }
            )
        }
    }

    // 备份版本选择对话框（独立于冲突对话框）
    if (showBackupDialog) {
        BackupVersionDialog(
            versions = backupVersions,
            onSelect = { version ->
                scope.launch {
                    val cfg = backupConfig ?: return@launch
                    syncMessage = "正在恢复 ${version.displayName}..."
                    isSyncing = true
                    val result = app.webDavManager.restoreFromBackup(cfg, app.dbManager, version)
                    isSyncing = false
                    syncMessage = when (result) {
                        is SyncResult.Success -> "✅ 已恢复到 ${version.displayName}，请重启应用"
                        is SyncResult.Error -> "❌ 恢复失败: ${result.message}"
                        else -> "恢复完成"
                    }
                    showBackupDialog = false
                }
            },
            onDismiss = { showBackupDialog = false }
        )
    }
}

// 多文件冲突解决对话框
@Composable
fun MultiConflictResolveDialog(
    conflictFiles: List<ConflictFile>,
    onResolve: (useLocal: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Sync, contentDescription = null) },
        title = { Text("检测到多文件冲突", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("以下 ${conflictFiles.size} 个文件本地与云端不一致：")
                Spacer(modifier = Modifier.height(8.dp))
                conflictFiles.forEach { cf ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(cf.fileName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("请选择以哪端数据为准：", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column {
                TextButton(
                    onClick = { onResolve(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📱 以本地数据为准")
                }
                TextButton(
                    onClick = { onResolve(false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("☁️ 以云端数据为准")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// 冲突解决对话框
@Composable
fun ConflictResolveDialog(
    localTimeStr: String,
    remoteTimeStr: String,
    onResolve: (useLocal: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Sync, contentDescription = null) },
        title = { Text("检测到冲突", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("本地数据最后修改时间：")
                Text(localTimeStr, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("云端数据最后修改时间：")
                Text(remoteTimeStr, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(16.dp))
                Text("请选择以哪端数据为准：")
            }
        },
        confirmButton = {
            Column {
                TextButton(
                    onClick = { onResolve(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📱 以本地数据为准")
                }
                TextButton(
                    onClick = { onResolve(false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("☁️ 以云端数据为准")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// 备份版本选择对话框
@Composable
fun BackupVersionDialog(
    versions: List<BackupVersion>,
    onSelect: (BackupVersion) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.History, contentDescription = null) },
        title = { Text("选择备份版本", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("云端共有 ${versions.size} 个备份版本：")
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(versions.size) { index ->
                        val version = versions[index]
                        Surface(
                            onClick = { onSelect(version) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CalendarToday,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(version.displayName, fontWeight = FontWeight.Medium)
                                    Text(
                                        "文件名: ${version.fileName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (index < versions.size - 1) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ——— 工具组件 ———

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun CategorySettingsItem(category: Category, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${category.name} (${if (category.type == TransactionType.INCOME) "收入" else "支出"})",
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun SetPasswordDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置密码") },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = "" },
                    label = { Text("新密码（至少4位）") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = "" },
                    label = { Text("确认密码") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    password.length < 4 -> error = "密码至少4位"
                    password != confirm -> error = "两次密码不一致"
                    else -> onConfirm(password)
                }
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun AddCategoryDialog(onConfirm: (Category) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加分类") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TransactionType.EXPENSE,
                        onClick = { type = TransactionType.EXPENSE },
                        label = { Text("支出") }
                    )
                    FilterChip(
                        selected = type == TransactionType.INCOME,
                        onClick = { type = TransactionType.INCOME },
                        label = { Text("收入") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onConfirm(Category(name = name.trim(), type = type))
                }
            }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ——— 数据导入导出 ———

/**
 * 导入数据：自动检测 zip 或 db 格式
 */
suspend fun importData(context: Context, uri: Uri): Boolean {
    return try {
        // 复制到临时文件
        val tempFile = File(context.cacheDir, "import_temp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { input.copyTo(it) }
        }

        val success = when {
            DataExporter.isZipFile(tempFile) -> {
                AppLogger.i("SettingsScreen", "检测到 zip 格式，使用多文件导入")
                DataExporter.importFromZip(context, tempFile)
            }
            tempFile.exists() && tempFile.length() > 0 -> {
                // 不管是未加密 SQLite 还是加密 SQLCipher .db，都尝试导入
                AppLogger.i("SettingsScreen", "检测到 db 文件，使用迁移导入")
                DataExporter.importLegacyDb(context, tempFile)
            }
            else -> {
                AppLogger.w("SettingsScreen", "无法识别的文件格式")
                false
            }
        }

        tempFile.delete()
        success
    } catch (e: Exception) {
        AppLogger.e("SettingsScreen", "导入失败", e)
        false
    }
}

/** 将所有日志合并写入用户选择的 Uri（分享/保存） */
suspend fun exportLogsToUri(context: Context, uri: Uri) {
    try {
        val exportFile = AppLogger.exportAll(context)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            exportFile.inputStream().use { it.copyTo(out) }
        }
        exportFile.delete()
    } catch (e: Exception) {
        AppLogger.e("SettingsScreen", "导出日志到 Uri 失败", e)
    }
}

// ——— 关于对话框 ———

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "简单记账",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "v 0.3.7",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                AboutInfoRow(label = "开发者", value = "EW")
                AboutInfoRow(label = "功能", value = "收支记录 · 统计分析 · 云端同步")
                AboutInfoRow(label = "数据存储", value = "按年分库 · Room 数据库")
                AboutInfoRow(label = "云同步", value = "WebDAV 多文件同步")
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "数据完全存储在本地，保护您的隐私安全。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}
