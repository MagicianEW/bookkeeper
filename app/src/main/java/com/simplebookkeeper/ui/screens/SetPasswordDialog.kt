package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.R

/**
 * 首启密码设置对话框
 */
@Composable
fun FirstLaunchSetPasswordDialog(
    onPasswordSet: (password: String) -> Unit,
    onSkip: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var showSkipConfirm by remember { mutableStateOf(false) }

    if (showSkipConfirm) {
        AlertDialog(
            onDismissRequest = { showSkipConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.skip_password_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(R.string.skip_password_warning))
            },
            confirmButton = {
                TextButton(onClick = {
                    showSkipConfirm = false
                    onSkip()
                }) {
                    Text(stringResource(R.string.confirm_skip), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(32.dp)) },
        title = {
            Text(
                stringResource(R.string.set_password_title),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.skip_password_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = "" },
                    label = { Text(stringResource(R.string.password_hint)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = "" },
                    label = { Text(stringResource(R.string.confirm_password)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(checked = visible, onCheckedChange = { visible = it })
                    Text(stringResource(R.string.show_password), style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            val minLenError = stringResource(R.string.password_min_length)
            val mismatchError = stringResource(R.string.password_mismatch)
            TextButton(onClick = {
                when {
                    password.length < 4 -> error = minLenError
                    password != confirm -> error = mismatchError
                    else -> onPasswordSet(password)
                }
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { showSkipConfirm = true }) { Text(stringResource(R.string.skip)) }
        }
    )
}
