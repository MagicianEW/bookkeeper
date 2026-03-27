package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.PaymentMethod
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.ui.theme.ExpenseRed
import com.simplebookkeeper.ui.theme.IncomeGreen
import com.simplebookkeeper.ui.theme.SavingBlue
import com.simplebookkeeper.ui.theme.WithdrawOrange
import com.simplebookkeeper.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTransactionScreen(
    viewModel: MainViewModel,
    transactionId: Long? = null,
    onBack: () -> Unit
) {
    val allCategories by viewModel.allCategories.collectAsState()
    val isEditMode = transactionId != null

    // 表单状态
    var selectedType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableLongStateOf(0L) }
    var selectedPayment by remember { mutableStateOf(PaymentMethod.WECHAT) }
    var note by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(Date()) }
    var showDatePicker by remember { mutableStateOf(false) }
    // 编辑模式下等待数据加载完成才渲染表单，避免分类列表未就绪时 selectedCategoryId 被重置
    var dataLoaded by remember { mutableStateOf(!isEditMode) }
    // 添加分类弹窗状态
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }

    // 编辑模式：从数据库加载原始数据并填入表单
    LaunchedEffect(transactionId) {
        if (transactionId != null) {
            val t = viewModel.getTransactionById(transactionId)
            if (t != null) {
                selectedType = t.type
                amountText = if (t.amount == t.amount.toLong().toDouble()) {
                    t.amount.toLong().toString()
                } else {
                    t.amount.toString()
                }
                selectedCategoryId = t.categoryId
                selectedPayment = t.paymentMethod
                note = t.note
                selectedDate = t.date
            }
            dataLoaded = true
        }
    }

    val filteredCategories = allCategories.filter { it.type == selectedType }
    // 仅新增模式下，分类列表加载后自动选第一个；编辑模式已由 LaunchedEffect 回填，不覆盖
    if (!isEditMode && selectedCategoryId == 0L && filteredCategories.isNotEmpty()) {
        selectedCategoryId = filteredCategories.first().id
    }

    val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "编辑记录" else "记账", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (!dataLoaded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 类型切换：支出/收入/储蓄/支取
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TypeTab(
                    text = "支出",
                    selected = selectedType == TransactionType.EXPENSE,
                    color = ExpenseRed,
                    onClick = {
                        selectedType = TransactionType.EXPENSE
                        selectedCategoryId = 0L
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                TypeTab(
                    text = "收入",
                    selected = selectedType == TransactionType.INCOME,
                    color = IncomeGreen,
                    onClick = {
                        selectedType = TransactionType.INCOME
                        selectedCategoryId = 0L
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                TypeTab(
                    text = "储蓄",
                    selected = selectedType == TransactionType.SAVING,
                    color = SavingBlue,
                    onClick = {
                        selectedType = TransactionType.SAVING
                        selectedCategoryId = 0L
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                TypeTab(
                    text = "支取",
                    selected = selectedType == TransactionType.WITHDRAW,
                    color = WithdrawOrange,
                    onClick = {
                        selectedType = TransactionType.WITHDRAW
                        selectedCategoryId = 0L
                    }
                )
            }

            // 金额输入
            OutlinedTextField(
                value = amountText,
                onValueChange = { v ->
                    if (v.matches(Regex("^\\d{0,10}(\\.\\d{0,2})?\$"))) amountText = v
                },
                label = { Text("金额") },
                prefix = { Text("¥") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 日期选择
            OutlinedTextField(
                value = dateFormat.format(selectedDate),
                onValueChange = {},
                label = { Text("日期") },
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true },
                enabled = false,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 分类选择
            Text("分类", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.heightIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredCategories) { category ->
                    CategoryChip(
                        category = category,
                        selected = selectedCategoryId == category.id,
                        onClick = { selectedCategoryId = category.id }
                    )
                }
                // 末尾添加"＋"按钮
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddCategoryDialog = true }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "添加分类",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 付款方式
            Text("付款方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    PaymentMethod.WECHAT to "微信",
                    PaymentMethod.ALIPAY to "支付宝",
                    PaymentMethod.CASH to "现金",
                    PaymentMethod.BANK_CARD to "银行卡"
                ).forEach { (method, label) ->
                    FilterChip(
                        selected = selectedPayment == method,
                        onClick = { selectedPayment = method },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 备注
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 保存按钮
            Button(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@Button
                    if (amount <= 0 || selectedCategoryId == 0L) return@Button

                    // 储蓄/支取需要特殊处理
                    when (selectedType) {
                        TransactionType.SAVING -> {
                            // 储蓄：从余额中扣除，添加到储蓄池
                            viewModel.addSaving(amount) {
                                // 同时记录一笔支出类型的交易（类别为"储蓄"）
                                val savingTransaction = Transaction(
                                    id = 0L,
                                    type = TransactionType.EXPENSE,
                                    amount = amount,
                                    categoryId = selectedCategoryId,
                                    paymentMethod = selectedPayment,
                                    note = note.trim().ifBlank { "储蓄" },
                                    date = selectedDate
                                )
                                viewModel.addTransaction(savingTransaction) { onBack() }
                            }
                        }
                        TransactionType.WITHDRAW -> {
                            // 支取：从储蓄池取出，添加到收入
                            viewModel.withdrawFromSavings(amount) { success ->
                                if (success) {
                                    // 同时记录一笔收入类型的交易
                                    val withdrawTransaction = Transaction(
                                        id = 0L,
                                        type = TransactionType.INCOME,
                                        amount = amount,
                                        categoryId = selectedCategoryId,
                                        paymentMethod = selectedPayment,
                                        note = note.trim().ifBlank { "支取" },
                                        date = selectedDate
                                    )
                                    viewModel.addTransaction(withdrawTransaction) { onBack() }
                                }
                            }
                        }
                        else -> {
                            // 普通支出/收入
                            val transaction = Transaction(
                                id = transactionId ?: 0L,
                                type = selectedType,
                                amount = amount,
                                categoryId = selectedCategoryId,
                                paymentMethod = selectedPayment,
                                note = note.trim(),
                                date = selectedDate
                            )
                            if (isEditMode) {
                                viewModel.updateTransaction(transaction) { onBack() }
                            } else {
                                viewModel.addTransaction(transaction) { onBack() }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 日期选择器
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.time
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        selectedDate = Date(it)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // 添加分类弹窗
    if (showAddCategoryDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddCategoryDialog = false
                newCategoryName = ""
            },
            title = { Text("添加分类") },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newCategoryName.trim()
                        if (name.isNotEmpty()) {
                            val newCategory = com.simplebookkeeper.data.model.Category(
                                name = name,
                                type = selectedType
                            )
                            viewModel.addCategory(newCategory)
                        }
                        showAddCategoryDialog = false
                        newCategoryName = ""
                    },
                    enabled = newCategoryName.trim().isNotEmpty()
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddCategoryDialog = false
                    newCategoryName = ""
                }) { Text("取消") }
            }
        )
    }
}

@Composable
fun TypeTab(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) color else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .width(100.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 10.dp)) {
            Text(
                text = text,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
fun CategoryChip(category: Category, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
            Text(
                text = category.name,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
