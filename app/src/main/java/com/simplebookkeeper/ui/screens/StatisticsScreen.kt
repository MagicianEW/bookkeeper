package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.ui.components.TransactionItem
import com.simplebookkeeper.ui.theme.ExpenseRed
import com.simplebookkeeper.ui.theme.IncomeGreen
import com.simplebookkeeper.ui.theme.SavingBlue
import com.simplebookkeeper.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    viewModel: MainViewModel,
    onTransactionClick: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    var selectedYear by remember { mutableIntStateOf(currentYear) }
    var yearlyIncome by remember { mutableDoubleStateOf(0.0) }
    var yearlyExpense by remember { mutableDoubleStateOf(0.0) }
    var yearlySavings by remember { mutableDoubleStateOf(0.0) }
    val availableYears by viewModel.availableYears.collectAsState()
    val categoriesMap by viewModel.categoriesMap.collectAsState()

    // 年度汇总
    LaunchedEffect(selectedYear) {
        scope.launch {
            val (income, expense) = viewModel.getYearlySummary(selectedYear)
            yearlyIncome = income
            yearlyExpense = expense
            yearlySavings = viewModel.getYearlySavings(selectedYear)
        }
    }

    // 当年账目
    val yearTransactions by viewModel.getTransactionsByYear(selectedYear).collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "统计",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(16.dp)
            )
        }

        // 年份切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { selectedYear-- }) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "上一年")
            }
            Text(
                "${selectedYear}年",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            IconButton(onClick = { selectedYear++ }) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "下一年")
            }
        }

        // 年度汇总卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${selectedYear}年汇总", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    YearStatItem("年收入", yearlyIncome, IncomeGreen)
                    YearStatItem("年支出", yearlyExpense, ExpenseRed)
                    YearStatItem("年结余", yearlyIncome - yearlyExpense,
                        if (yearlyIncome >= yearlyExpense) IncomeGreen else ExpenseRed)
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    YearStatItem("年储蓄", yearlySavings, SavingBlue)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        Text(
            "明细列表",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(yearTransactions, key = { it.id }) { transaction ->
                TransactionItem(
                    transaction = transaction,
                    category = categoriesMap[transaction.categoryId],
                    onClick = { onTransactionClick(transaction.id) }
                )
            }
        }
    }
}

@Composable
fun YearStatItem(label: String, amount: Double, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text("¥%.2f".format(amount), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
    }
}
