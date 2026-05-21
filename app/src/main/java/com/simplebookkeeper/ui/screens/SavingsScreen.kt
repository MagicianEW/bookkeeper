package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.model.Saving
import com.simplebookkeeper.data.model.SavingType
import com.simplebookkeeper.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsScreen(viewModel: MainViewModel) {
    val balance by viewModel.savingsBalance.collectAsState()
    val savings by viewModel.allSavings.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<Saving?>(null) }

    // 按日分组
    val groupedSavings = savings.groupBy { saving ->
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(saving.date)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏（与其他页面风格统一）
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.savings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // 余额卡片
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.savings_balance), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "¥%,.2f".format(balance / 100.0),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 按日分组的记录列表
            if (savings.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Savings,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.no_savings),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.add_first_saving),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    groupedSavings.forEach { (dateStr, daySavings) ->
                        item {
                            Text(
                                dateStr,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(daySavings) { saving ->
                            SavingItem(
                                saving = saving,
                                onLongClick = { showDeleteConfirm = saving }
                            )
                        }
                    }
                }
            }
        }

        // 悬浮添加按钮
        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_saving), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }

    // 添加储蓄记录对话框
    if (showAddDialog) {
        AddSavingDialog(
            currentBalance = balance,
            onConfirm = { type, amount, note, date ->
                viewModel.addSaving(type, amount, note, date)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // 删除确认对话框
    showDeleteConfirm?.let { saving ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_record)) },
            text = {
                Text(stringResource(R.string.delete_saving_confirm, if (saving.type == SavingType.DEPOSIT) stringResource(R.string.deposit) else stringResource(R.string.withdraw)))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSaving(saving)
                    showDeleteConfirm = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavingItem(saving: Saving, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (saving.type == SavingType.DEPOSIT) Icons.Default.Savings else Icons.Default.SouthWest,
                contentDescription = null,
                tint = if (saving.type == SavingType.DEPOSIT)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (saving.type == SavingType.DEPOSIT) stringResource(R.string.deposit) else stringResource(R.string.withdraw),
                    fontWeight = FontWeight.Medium
                )
                if (saving.note.isNotBlank()) {
                    Text(
                        saving.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "${if (saving.type == SavingType.DEPOSIT) "+" else "-"}¥%,.2f".format(saving.amount / 100.0),
                fontWeight = FontWeight.Medium,
                color = if (saving.type == SavingType.DEPOSIT)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
        }
    }
}
