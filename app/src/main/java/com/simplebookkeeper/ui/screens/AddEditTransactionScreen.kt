package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.PaymentMethod
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.ui.theme.ExpenseRed
import com.simplebookkeeper.ui.theme.IncomeGreen
import com.simplebookkeeper.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 编辑模式：从数据库加载原始数据并填入表单
    LaunchedEffect(transactionId) {
        if (transactionId != null) {
            val t = viewModel.getTransactionById(transactionId)
            if (t != null) {
                selectedType = t.type
                // amount 存为分，显示时转元
                val yuanAmount = t.amount / 100.0
                amountText = if (yuanAmount == yuanAmount.toLong().toDouble()) {
                    yuanAmount.toLong().toString()
                } else {
                    yuanAmount.toString()
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

    val dateFormat = SimpleDateFormat(stringResource(R.string.date_format), Locale.getDefault())

    val titleEdit = stringResource(R.string.edit_record_title)
    val titleAdd = stringResource(R.string.add_record_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) titleEdit else titleAdd, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (isEditMode && transactionId != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
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
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp)
        ) {
            // 收入/支出切换
            val expenseText = stringResource(R.string.expense)
            val incomeText = stringResource(R.string.income)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TypeTab(
                    text = expenseText,
                    selected = selectedType == TransactionType.EXPENSE,
                    color = ExpenseRed,
                    onClick = {
                        selectedType = TransactionType.EXPENSE
                        selectedCategoryId = 0L
                    }
                )
                Spacer(modifier = Modifier.width(16.dp))
                TypeTab(
                    text = incomeText,
                    selected = selectedType == TransactionType.INCOME,
                    color = IncomeGreen,
                    onClick = {
                        selectedType = TransactionType.INCOME
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
                label = { Text(stringResource(R.string.amount)) },
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
                label = { Text(stringResource(R.string.date)) },
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
            Text(stringResource(R.string.category), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredCategories.forEach { category ->
                        CategoryChip(
                            category = category,
                            selected = selectedCategoryId == category.id,
                            onClick = { selectedCategoryId = category.id }
                        )
                    }
                    // 末尾添加"＋"按钮
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { showAddCategoryDialog = true }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_category_icon),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 付款方式
            Text(stringResource(R.string.payment_method), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    PaymentMethod.WECHAT to stringResource(R.string.wechat),
                    PaymentMethod.ALIPAY to stringResource(R.string.alipay),
                    PaymentMethod.CASH to stringResource(R.string.cash),
                    PaymentMethod.BANK_CARD to stringResource(R.string.card)
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
                label = { Text(stringResource(R.string.note_optional)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 保存按钮
            Button(
                onClick = {
                    val amountYuan = amountText.toDoubleOrNull() ?: return@Button
                    if (amountYuan <= 0 || selectedCategoryId == 0L) return@Button
                    val transaction = Transaction(
                        id = transactionId ?: 0L,
                        type = selectedType,
                        amount = (amountYuan * 100).toLong(),  // 元 → 分
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
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.save), fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
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
            title = { Text(stringResource(R.string.add_category)) },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val catName = newCategoryName.trim()
                        if (catName.isNotEmpty()) {
                            val newCat = com.simplebookkeeper.data.model.Category(
                                name = catName,
                                type = selectedType
                            )
                            viewModel.addCategory(newCat)
                            showAddCategoryDialog = false
                            newCategoryName = ""
                        }
                        showAddCategoryDialog = false
                        newCategoryName = ""
                    },
                    enabled = newCategoryName.trim().isNotEmpty()
                ) { Text(stringResource(R.string.add_button)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddCategoryDialog = false
                    newCategoryName = ""
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 删除确认对话框
    if (showDeleteDialog && transactionId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_record_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.delete_record_irreversible)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTransactionById(transactionId) {
                            onBack()
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
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
            .defaultMinSize(minWidth = 72.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
