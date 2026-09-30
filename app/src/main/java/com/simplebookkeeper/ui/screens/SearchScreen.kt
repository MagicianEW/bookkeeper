package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.ui.components.TransactionItem
import com.simplebookkeeper.util.DateTimeUtils
import com.simplebookkeeper.util.Money
import com.simplebookkeeper.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val searchDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onTransactionClick: (Long) -> Unit
) {
    val searchState by viewModel.searchState.collectAsState()
    val allCategories by viewModel.allCategories.collectAsState()
    val categoriesMap by viewModel.categoriesMap.collectAsState()

    var keyword by remember { mutableStateOf("") }
    var minAmount by remember { mutableStateOf("") }
    var maxAmount by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<TransactionType?>(null) }
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var startDateText by remember { mutableStateOf("") }
    var endDateText by remember { mutableStateOf("") }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf<Date?>(null) }
    var endDate by remember { mutableStateOf<Date?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.search_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(16.dp)
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            // 关键词
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 金额范围
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minAmount,
                    onValueChange = { minAmount = it },
                    label = { Text(stringResource(R.string.min_amount)) },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = maxAmount,
                    onValueChange = { maxAmount = it },
                    label = { Text(stringResource(R.string.max_amount)) },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 日期范围
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startDateText,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.start_date)) },
                    readOnly = true,
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showStartPicker = true }) {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
                    }
                )
                OutlinedTextField(
                    value = endDateText,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.end_date)) },
                    readOnly = true,
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showEndPicker = true }) {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 收支类型
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to stringResource(R.string.all), TransactionType.EXPENSE to stringResource(R.string.expense), TransactionType.INCOME to stringResource(R.string.income)).forEach { (type, label) ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 分类筛选
            val filteredCats = if (selectedType != null)
                allCategories.filter { it.type == selectedType }
            else allCategories

            if (filteredCats.isNotEmpty()) {
                Text(stringResource(R.string.category), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 旧实现 take(5)：自定义分类多于 5 个时其余无法按分类检索，
                // 与"四维度模糊搜索"的承诺不符。改为可横向滚动的完整列表。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { selectedCategoryId = null },
                        label = { Text(stringResource(R.string.all)) }
                    )
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredCats, key = { it.id }) { cat ->
                        FilterChip(
                            selected = selectedCategoryId == cat.id,
                            onClick = { selectedCategoryId = if (selectedCategoryId == cat.id) null else cat.id },
                            label = { Text(cat.name) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 搜索按钮
            Button(
                onClick = {
                    hasSearched = true
                    viewModel.search(
                        startDate = startDate?.let { DateTimeUtils.toStartOfDayMillis(it) },
                        endDate = endDate?.let { DateTimeUtils.toEndOfDayMillis(it) },
                        minAmount = Money.yuanToFen(minAmount),
                        maxAmount = Money.yuanToFen(maxAmount),
                        type = selectedType,
                        categoryId = selectedCategoryId,
                        keyword = keyword.ifBlank { null }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.search_button))
            }
        }

        HorizontalDivider()

        // 结果
        if (searchState.isSearching) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (hasSearched) {
            if (searchState.results.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else {
                Text(
                    stringResource(R.string.result_count, searchState.results.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn {
                    items(searchState.results, key = { it.id }) { transaction ->
                        TransactionItem(
                            transaction = transaction,
                            category = categoriesMap[transaction.categoryId],
                            paymentMethodLabel = transaction.customPaymentMethod,
                            onClick = { onTransactionClick(transaction.id) }
                        )
                    }
                }
            }
        }
    }

    // 日期选择器
    if (showStartPicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        // DatePicker 是 UTC 语义，需换算成本地日期
                        startDate = DateTimeUtils.utcPickerMillisToLocalDate(it)
                        startDateText = searchDateFormat.format(startDate!!)
                    }
                    showStartPicker = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text(stringResource(R.string.cancel)) } }
        ) { DatePicker(state = state) }
    }

    if (showEndPicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        endDate = DateTimeUtils.utcPickerMillisToLocalDate(it)
                        endDateText = searchDateFormat.format(endDate!!)
                    }
                    showEndPicker = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { showEndPicker = false }) { Text(stringResource(R.string.cancel)) } }
        ) { DatePicker(state = state) }
    }
}
