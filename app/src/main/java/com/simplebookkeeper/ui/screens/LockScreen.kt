package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.R
import com.simplebookkeeper.util.AppLogger
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as BookkeeperApp
    val scope = rememberCoroutineScope()
    val activity = context as FragmentActivity

    val isBiometricEnabled by app.passwordManager.isBiometricEnabled.collectAsState(initial = false)
    val canUseBiometric = app.biometricAuth.canAuthenticate()

    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // 防重入：确保自动触发的 authenticate() 只被调用一次
    var biometricLaunched by remember { mutableStateOf(false) }

    // 自动触发生物识别
    // 使用 isBiometricEnabled 作为 key 确保 DataStore 真实值到来后才触发
    // BiometricAuth.authenticate() 内部会处理 Activity 生命周期等待
    LaunchedEffect(isBiometricEnabled) {
        if (isBiometricEnabled && canUseBiometric && !biometricLaunched) {
            biometricLaunched = true
            AppLogger.i("LockScreen", "自动触发生物识别")
            app.biometricAuth.authenticate(
                activity = activity,
                onSuccess = {
                    AppLogger.i("LockScreen", "生物识别成功，解锁")
                    onUnlocked()
                },
                onFailed = {
                    AppLogger.i("LockScreen", "生物识别失败")
                    // 允许用户通过按钮手动重试
                },
                onError = { code ->
                    AppLogger.i("LockScreen", "生物识别错误: $code")
                }
            )
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                stringResource(R.string.app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                stringResource(R.string.enter_password),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = ""
                },
                label = { Text(stringResource(R.string.password_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                isError = errorMessage.isNotEmpty(),
                supportingText = if (errorMessage.isNotEmpty()) {
                    { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    scope.launch {
                        val ok = app.passwordManager.verifyPassword(password)
                        if (ok) onUnlocked()
                        else {
                            errorMessage = context.getString(R.string.wrong_password)
                            password = ""
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = password.isNotEmpty()
            ) {
                Text(stringResource(R.string.unlock), fontSize = 16.sp)
            }

            if (isBiometricEnabled && canUseBiometric) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        AppLogger.i("LockScreen", "手动触发生物识别")
                        app.biometricAuth.authenticate(
                            activity = activity,
                            onSuccess = {
                                AppLogger.i("LockScreen", "生物识别成功（手动），解锁")
                                onUnlocked()
                            },
                            onFailed = {
                                AppLogger.i("LockScreen", "生物识别失败（手动）")
                            },
                            onError = { code ->
                                AppLogger.i("LockScreen", "生物识别错误（手动）: $code")
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.use_biometric))
                }
            }
        }
    }
}
