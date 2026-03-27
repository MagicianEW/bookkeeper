package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val savingsBalance by viewModel.savingsBalance.collectAsState()
    val monthlyTransactions by viewModel.monthlyTransactions.collectAsState()
    val categoriesMap by viewModel.categoriesMap.collectAsState()
    val displayYear by viewModel.displayYear.collectAsState()
    val displayMonth by viewModel.displayMonth.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("简单记账", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddTransaction,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "记账", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
            SummaryCard(income = monthlyIncome, expense = monthlyExpense, savings = savingsBalance)

            // 账目列表
            if (monthlyTransactions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "本月暂无记录\n点击右下角 + 开始记账",
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
    }

    // 删除确认对话框
    showDeleteDialog?.let { id ->
        val transaction = monthlyTransactions.find { it.id == id }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除记录") },
            text = { Text("确定要删除这条记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    transaction?.let { viewModel.deleteTransaction(it) }
                    showDeleteDialog = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消") }
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
            text = "${year}年${month}月",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "下月")
        }
    }
}
