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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.BuildConfig
import com.simplebookkeeper.R
import com.simplebookkeeper.data.DataExporter
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.data.repository.WebDavConfig
import com.simplebookkeeper.sync.SyncResult
import com.simplebookkeeper.sync.SyncWorker
import com.simplebookkeeper.ui.theme.LanguageMode
import com.simplebookkeeper.ui.theme.ThemeMode
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

    // 预提取 onClick 等非 Composable 作用域中需要的字符串
    val exportSuccessText = stringResource(R.string.export_success)
    val exportFailedText = stringResource(R.string.export_failed)
    val logExportSuccessText = stringResource(R.string.log_export_success)
    val importSuccessText = stringResource(R.string.import_success)
    val importFailedText = stringResource(R.string.import_failed)
    val pleaseConfigureWebdavText = stringResource(R.string.please_configure_webdav)
    val connectionFailedMsgTemplate = stringResource(R.string.connection_failed_message)
    val connectionSuccessText = stringResource(R.string.connection_success)
    val configSavedText = stringResource(R.string.config_saved)
    val syncSuccessText = stringResource(R.string.export_success)
    val syncFailedTemplate = stringResource(R.string.connection_failed_message)
    val syncCompleteText = stringResource(R.string.sync_complete)
    val passwordSetSuccessText = stringResource(R.string.password_set_success)
    val passwordErrorText = stringResource(R.string.password_error)

    // 密码相关
    val isPasswordEnabled by app.passwordManager.isPasswordEnabled.collectAsState(initial = false)
    val isBiometricEnabled by app.passwordManager.isBiometricEnabled.collectAsState(initial = false)
    val canUseBiometric = app.biometricAuth.canAuthenticate()

    // 主题模式
    val themeMode by app.settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    // 语言模式
    val languageMode by app.settingsRepository.languageMode.collectAsState(initial = LanguageMode.FOLLOW_SYSTEM)

    // WebDAV配置
    val webDavConfig by app.settingsRepository.webDavConfig.collectAsState(initial = WebDavConfig())
    var webDavUrl by remember(webDavConfig.url) { mutableStateOf(webDavConfig.url) }
    var webDavUsername by remember(webDavConfig.username) { mutableStateOf(webDavConfig.username) }
    var webDavPassword by remember(webDavConfig.password) { mutableStateOf(webDavConfig.password) }
    var webDavEnabled by remember(webDavConfig.enabled) { mutableStateOf(webDavConfig.enabled) }

    // 对话框状态
    var showSetPasswordDialog by remember { mutableStateOf(false) }
    var showDisablePasswordDialog by remember { mutableStateOf(false) }
    var showPasswordVerifyForDisable by remember { mutableStateOf(false) }
    var showWebDavSection by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var showPasswordVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    val allCategories by viewModel.allCategories.collectAsState()

    // 导出文件选择器（导出为 CSV 压缩包）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch {
                val tempFile = File(context.cacheDir, "bookkeeper_export.zip")
                // 如果设置了密码，需要让用户输入密码用于加密
                // 此处暂用 null（导出时不加密），后续可通过对话框输入密码
                val password: String? = null
                val success = DataExporter.exportToZip(context, tempFile, password)
                if (success) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                    syncMessage = exportSuccessText
                } else {
                    syncMessage = exportFailedText
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
                syncMessage = logExportSuccessText
            }
        }
    }

    // 导入文件选择器（支持 zip）
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val success = importData(context, it)
                syncMessage = if (success) importSuccessText else importFailedText
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
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showAboutDialog = true }) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(R.string.about),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ——— 外观 ———
            item { SettingsSectionHeader(stringResource(R.string.appearance)) }
            item {
                ThemeModeSelector(
                    currentMode = themeMode,
                    onModeChange = { mode ->
                        scope.launch { app.settingsRepository.saveThemeMode(mode) }
                    }
                )
            }

            // ——— 语言 ———
            item { SettingsSectionHeader(stringResource(R.string.language)) }
            item {
                LanguageModeSelector(
                    currentMode = languageMode,
                    onModeChange = { mode ->
                        scope.launch {
                            // 先保存语言设置，等待写入完成
                            app.settingsRepository.saveLanguageMode(mode)
                            // 更新静态变量，确保新 Activity 读到正确值
                            com.simplebookkeeper.ui.MainActivity.lastAppliedLanguage = mode
                            // 重启 Activity
                            val activity = context as? androidx.appcompat.app.AppCompatActivity
                            if (activity != null) {
                                val intent = activity.intent
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                activity.finish()
                                activity.startActivity(intent)
                            }
                        }
                    }
                )
            }

            // ——— 安全设置 ———
            item {
                SettingsSectionHeader(stringResource(R.string.security))
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Lock,
                    title = if (isPasswordEnabled) stringResource(R.string.change_password) else stringResource(R.string.set_password),
                    subtitle = if (isPasswordEnabled) stringResource(R.string.password_set) else stringResource(R.string.password_not_set),
                    onClick = { showSetPasswordDialog = true }
                )
            }
            if (isPasswordEnabled) {
                item {
                    SettingsItem(
                        icon = Icons.Default.LockOpen,
                        title = stringResource(R.string.close_password),
                        subtitle = stringResource(R.string.cannot_sync_encrypted),
                        onClick = { showDisablePasswordDialog = true }
                    )
                }
                if (canUseBiometric) {
                    item {
                        SettingsSwitchItem(
                            icon = Icons.Default.Fingerprint,
                            title = stringResource(R.string.biometric),
                            subtitle = stringResource(R.string.biometric_disabled),
                            checked = isBiometricEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
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
                                    val activity = context as? FragmentActivity
                                    if (activity != null) {
                                        app.biometricAuth.authenticate(
                                            activity = activity,
                                            onSuccess = {
                                                scope.launch { app.passwordManager.setBiometricEnabled(false) }
                                            },
                                            onFailed = {
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
            item { SettingsSectionHeader(stringResource(R.string.webdav_sync)) }
            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Cloud,
                    title = stringResource(R.string.webdav_sync),
                    subtitle = if (webDavEnabled && webDavUrl.isNotBlank()) stringResource(R.string.configured_prefix, webDavUrl.take(30)) else stringResource(R.string.not_configured),
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
                    title = stringResource(R.string.configure_webdav),
                    subtitle = stringResource(R.string.configure_webdav_subtitle),
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
                                            syncMessage = if (result.isSuccess) connectionSuccessText else connectionFailedMsgTemplate.format(result.exceptionOrNull()?.message)
                                            isTesting = false
                                        }
                                    },
                                    enabled = !isTesting && webDavUrl.isNotBlank()
                                ) {
                                    if (isTesting) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    else Text(stringResource(R.string.test_connection))
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            app.settingsRepository.saveWebDavConfig(
                                                WebDavConfig(webDavUrl, webDavUsername, webDavPassword, webDavEnabled)
                                            )
                                            syncMessage = configSavedText
                                            showWebDavSection = false
                                        }
                                    }
                                ) { Text(stringResource(R.string.save_config)) }
                            }
                        }
                    }
                }
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Sync,
                    title = if (isSyncing) stringResource(R.string.syncing) else stringResource(R.string.sync_now),
                    subtitle = if (isSyncing) stringResource(R.string.syncing_subtitle) else stringResource(R.string.sync_subtitle),
                    onClick = {
                        if (isSyncing) return@SettingsItem
                        scope.launch {
                            val config = app.settingsRepository.webDavConfig.first()
                            if (!config.enabled || config.url.isBlank()) {
                                syncMessage = pleaseConfigureWebdavText
                                return@launch
                            }
                            isSyncing = true
                            syncMessage = null
                            viewModel.syncNow(config) { result ->
                                syncMessage = when (result) {
                                    is SyncResult.Success -> syncSuccessText
                                    is SyncResult.Error -> syncFailedTemplate.format(result.message)
                                    else -> syncCompleteText
                                }
                                isSyncing = false
                            }
                        }
                    }
                )
            }

            // ——— 数据管理 ———
            item { SettingsSectionHeader(stringResource(R.string.data_management)) }
            item {
                SettingsItem(
                    icon = Icons.Default.Upload,
                    title = stringResource(R.string.export_csv),
                    subtitle = stringResource(R.string.export_csv_subtitle),
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLauncher.launch("bookkeeper_$timestamp.zip")
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Download,
                    title = stringResource(R.string.import_csv),
                    subtitle = stringResource(R.string.import_csv_subtitle),
                    onClick = { importLauncher.launch("application/zip") }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Description,
                    title = stringResource(R.string.export_log),
                    subtitle = stringResource(R.string.export_log_subtitle),
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLogLauncher.launch("bookkeeper_log_$timestamp.txt")
                    }
                )
            }

            // ——— 分类管理 ———
            item { SettingsSectionHeader(stringResource(R.string.category_management)) }
            item {
                SettingsItem(
                    icon = Icons.Default.Add,
                    title = stringResource(R.string.add_custom_category),
                    subtitle = stringResource(R.string.add_custom_category_subtitle),
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
                TextButton(onClick = { syncMessage = null }) { Text(stringResource(R.string.close)) }
            }
        ) { Text(msg) }
    }

    // 设置密码对话框
    if (showSetPasswordDialog) {
        SetPasswordDialog(
            onConfirm = { newPassword ->
                scope.launch {
                    app.passwordManager.setPassword(newPassword)
                    syncMessage = passwordSetSuccessText
                }
                showSetPasswordDialog = false
            },
            onDismiss = { showSetPasswordDialog = false }
        )
    }

    // 关闭应用锁密码确认
    if (showDisablePasswordDialog) {
        AlertDialog(
            onDismissRequest = { showDisablePasswordDialog = false },
            title = { Text(stringResource(R.string.close_password_protection)) },
            text = { Text(stringResource(R.string.close_password_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.passwordManager.disablePassword() }
                    showDisablePasswordDialog = false
                }) { Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDisablePasswordDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 关闭生物识别时：密码验证对话框
    if (showPasswordVerifyForDisable) {
        var passwordInput by remember { mutableStateOf("") }
        var verifyError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showPasswordVerifyForDisable = false },
            title = { Text(stringResource(R.string.verify_password_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.enter_password_to_disable_biometric), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it; verifyError = null },
                        label = { Text(stringResource(R.string.webdav_password)) },
                        singleLine = true,
                        isError = verifyError != null,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                scope.launch {
                                    val ok = app.passwordManager.verifyPassword(passwordInput)
                                    if (ok) {
                                        app.passwordManager.setBiometricEnabled(false)
                                        showPasswordVerifyForDisable = false
                                    } else {
                                        verifyError = passwordErrorText
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
                            verifyError = passwordErrorText
                        }
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordVerifyForDisable = false }) { Text(stringResource(R.string.cancel)) }
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
    val incomeText = stringResource(R.string.income)
    val expenseText = stringResource(R.string.expense)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${category.name} (${if (category.type == TransactionType.INCOME) incomeText else expenseText})",
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun SetPasswordDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    // 预提取 onClick 中需要的字符串
    val passwordMinLengthError = stringResource(R.string.password_min_length)
    val passwordMismatchError = stringResource(R.string.password_mismatch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_password_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = "" },
                    label = { Text(stringResource(R.string.new_password)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = "" },
                    label = { Text(stringResource(R.string.confirm_password)) },
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
                    password.length < 4 -> error = passwordMinLengthError
                    password != confirm -> error = passwordMismatchError
                    else -> onConfirm(password)
                }
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun AddCategoryDialog(onConfirm: (Category) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_category)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TransactionType.EXPENSE,
                        onClick = { type = TransactionType.EXPENSE },
                        label = { Text(stringResource(R.string.expense)) }
                    )
                    FilterChip(
                        selected = type == TransactionType.INCOME,
                        onClick = { type = TransactionType.INCOME },
                        label = { Text(stringResource(R.string.income)) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onConfirm(Category(name = name.trim(), type = type))
                }
            }) { Text(stringResource(R.string.add_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ——— 数据导入导出 ———

suspend fun importData(context: Context, uri: Uri): Boolean {
    return try {
        val tempFile = File(context.cacheDir, "import_temp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { input.copyTo(it) }
        }

        val app = context.applicationContext as? com.simplebookkeeper.BookkeeperApp
        // 导入时暂不传密码，后续可通过对话框输入
        val success = DataExporter.importFromZip(context, tempFile, null)
        tempFile.delete()
        success
    } catch (e: Exception) {
        AppLogger.e("SettingsScreen", "导入失败", e)
        false
    }
}

/** 将所有日志合并写入用户选择的 Uri */
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
                stringResource(R.string.app_name),
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
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                AboutInfoRow(label = stringResource(R.string.about_developer), value = "EW")
                AboutInfoRow(label = stringResource(R.string.about_features_label), value = stringResource(R.string.about_features))
                AboutInfoRow(label = stringResource(R.string.about_data_storage), value = stringResource(R.string.about_storage))
                AboutInfoRow(label = stringResource(R.string.about_cloud_sync), value = stringResource(R.string.about_sync))
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.about_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
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

@Composable
private fun ThemeModeSelector(
    currentMode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(stringResource(R.string.theme_mode), fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == currentMode,
                        onClick = { onModeChange(mode) }
                    )
                    Text(
                        stringResource(mode.displayNameResId),
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageModeSelector(
    currentMode: LanguageMode,
    onModeChange: (LanguageMode) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(stringResource(R.string.language), fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            LanguageMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == currentMode,
                        onClick = { onModeChange(mode) }
                    )
                    Text(
                        stringResource(mode.displayNameResId),
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
