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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.simplebookkeeper.BookkeeperApp
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as BookkeeperApp
    val scope = rememberCoroutineScope()

    val isBiometricEnabled by app.passwordManager.isBiometricEnabled.collectAsState(initial = false)
    val canUseBiometric = app.biometricAuth.canAuthenticate()

    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var showBiometric by remember { mutableStateOf(false) }

    // 自动触发生物识别
    LaunchedEffect(Unit) {
        if (isBiometricEnabled && canUseBiometric) {
            showBiometric = true
        }
    }

    if (showBiometric) {
        LaunchedEffect(Unit) {
            app.biometricAuth.authenticate(
                activity = context as FragmentActivity,
                onSuccess = { onUnlocked() },
                onFailed = { showBiometric = false },
                onError = { code ->
                    showBiometric = false
                    if (code != "USE_PASSWORD") errorMessage = code
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
                "简单记账",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                "请输入密码解锁",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = ""
                },
                label = { Text("密码") },
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
                            errorMessage = "密码错误，请重试"
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
                Text("解锁", fontSize = 16.sp)
            }

            if (isBiometricEnabled && canUseBiometric) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showBiometric = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("使用生物识别")
                }
            }
        }
    }
}
