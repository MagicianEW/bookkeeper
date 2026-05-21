package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.R
import com.simplebookkeeper.ui.components.SummaryCard
import com.simplebookkeeper.ui.components.TransactionItem
import com.simplebookkeeper.viewmodel.MainViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onAddTransaction: () -> Unit,
    onTransactionClick: (Long) -> Unit
) {
    val monthlyIncome by viewModel.monthlyIncome.collectAsState()
    val monthlyExpense by viewModel.monthlyExpense.collectAsState()
    val monthlyTransactions by viewModel.monthlyTransactions.collectAsState()
    val categoriesMap by viewModel.categoriesMap.collectAsState()
    val displayYear by viewModel.displayYear.collectAsState()
    val displayMonth by viewModel.displayMonth.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<Long?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏（与搜索/统计/设置页风格统一）
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
                    stringResource(R.string.book_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        isRefreshing = true
                        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                        val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
                        viewModel.setDisplayMonth(currentYear, currentMonth)
                        isRefreshing = false
                    }
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // 月份切换
            MonthSelector(
                year = displayYear,
                month = displayMonth,
                onPrevious = {
                    val cal = Calendar.getInstance()
                    cal.set(displayYear, displayMonth - 1, 1)
                    cal.add(Calendar.MONTH, -1)
                    viewModel.setDisplayMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                },
                onNext = {
                    val cal = Calendar.getInstance()
                    cal.set(displayYear, displayMonth - 1, 1)
                    cal.add(Calendar.MONTH, 1)
                    viewModel.setDisplayMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                }
            )

            // 汇总卡片
            SummaryCard(income = monthlyIncome, expense = monthlyExpense)

            // 账目列表
            if (monthlyTransactions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_records),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(
                        items = monthlyTransactions,
                        key = { it.id }
                    ) { transaction ->
                        TransactionItem(
                            transaction = transaction,
                            category = categoriesMap[transaction.categoryId],
                            onClick = { onTransactionClick(transaction.id) },
                            onLongClick = { showDeleteDialog = transaction.id }
                        )
                    }
                }
            }
        }

        // 悬浮记账按钮
        FloatingActionButton(
            onClick = onAddTransaction,
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_record), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }

    // 删除确认对话框
    showDeleteDialog?.let { id ->
        val transaction = monthlyTransactions.find { it.id == id }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.delete_record)) },
            text = { Text(stringResource(R.string.delete_record_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    transaction?.let { viewModel.deleteTransaction(it) }
                    showDeleteDialog = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
fun MonthSelector(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "上月")
        }
        Text(
            text = stringResource(R.string.month_format, year, month),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "下月")
        }
    }
}
