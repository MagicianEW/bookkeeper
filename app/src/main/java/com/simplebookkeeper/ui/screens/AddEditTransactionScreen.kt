package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.PaymentMethod
import com.simplebookkeeper.data.model.PaymentMethodEntity
import com.simplebookkeeper.util.AppLogger
import com.simplebookkeeper.util.DateTimeUtils
import com.simplebookkeeper.util.Money
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
    var isSaving by remember { mutableStateOf(false) }
    // 编辑模式下保留原始 createdAt —— @Update 是整行覆盖，
    // 若不带上会丢失"这笔记录最初是什么时候记的"
    var originalCreatedAt by remember { mutableLongStateOf(0L) }

    // 支付方式状态
    var selectedCustomMethod by remember { mutableStateOf<String?>(null) }
    var customMethods by remember { mutableStateOf<List<PaymentMethodEntity>>(emptyList()) }
    var showAddMethodDialog by remember { mutableStateOf(false) }
    var newMethodName by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    var noteFieldFocused by remember { mutableStateOf(false) }
    val noteItemIndex = 13
    LaunchedEffect(noteFieldFocused) {
        if (noteFieldFocused) {
            listState.animateScrollToItem(noteItemIndex)
        }
    }

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
                selectedCustomMethod = t.customPaymentMethod
                note = t.note
                selectedDate = t.date
                originalCreatedAt = t.createdAt
            }
            dataLoaded = true
        }
    }

    // 加载自定义支付/收款方式（只取 isDefault=0，不含预置方式）
    LaunchedEffect(selectedType) {
        viewModel.paymentMethodRepository.getCustomByType(selectedType).collect { list ->
            customMethods = list
        }
    }

    val filteredCategories = remember(selectedType, allCategories) {
        allCategories.filter { it.type == selectedType }
    }

    LaunchedEffect(isEditMode, filteredCategories) {
        if (!isEditMode && selectedCategoryId == 0L && filteredCategories.isNotEmpty()) {
            selectedCategoryId = filteredCategories.first().id
        }
    }

    val dateFormatString = stringResource(R.string.date_format)
    val dateFormat = remember(dateFormatString) { SimpleDateFormat(dateFormatString, Locale.getDefault()) }

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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .imePadding()
        ) {
            item {
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
            }

            item {
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
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
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
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                // 分类选择
                Text(stringResource(R.string.category), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
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
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                // 付款方式 / 收款方式（根据收支类型动态切换）
                val methodLabel = if (selectedType == TransactionType.INCOME) {
                    stringResource(R.string.collection_method)
                } else {
                    stringResource(R.string.payment_method)
                }
                Text(methodLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 标准方式（现金/微信/支付宝/银行卡，收支通用）
                    val standardMethods = listOf(
                        PaymentMethod.CASH to stringResource(R.string.cash),
                        PaymentMethod.WECHAT to stringResource(R.string.wechat),
                        PaymentMethod.ALIPAY to stringResource(R.string.alipay),
                        PaymentMethod.BANK_CARD to stringResource(R.string.card)
                    )
                    standardMethods.forEach { (method, label) ->
                        FilterChip(
                            selected = selectedPayment == method && selectedCustomMethod == null,
                            onClick = {
                                selectedPayment = method
                                selectedCustomMethod = null
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                    // 自定义方式：getCustomByType 已排除预置项，无需再按名字比对
                    // （旧逻辑用本地化字符串去比对库里的中文名，英文界面会失效并重复显示）
                    customMethods.forEach { custom ->
                        FilterChip(
                            selected = selectedCustomMethod == custom.name,
                            onClick = {
                                selectedPayment = PaymentMethod.OTHER
                                selectedCustomMethod = custom.name
                            },
                            label = { Text(custom.name, fontSize = 12.sp) }
                        )
                    }
                    // 添加自定义方式
                    AssistChip(
                        onClick = { showAddMethodDialog = true },
                        label = { Text(stringResource(R.string.add_custom_method), fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                // 备注
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.note_optional)) },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { noteFieldFocused = it.isFocused },
                    maxLines = 2
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            item {
                // 保存按钮
                Button(
                    onClick = {
                        // 校验全部通过后才置 isSaving —— 否则一次误触（金额为空/为 0、
                        // 未选分类）会让早退分支跳过复位，保存按钮在本页面彻底失效
                        val amountFen = Money.yuanToFen(amountText)
                        if (amountFen == null || amountFen <= 0L) {
                            AppLogger.i("AddEditTxScreen", "保存校验失败: amountText=$amountText")
                            return@Button
                        }
                        if (selectedCategoryId == 0L) {
                            AppLogger.i("AddEditTxScreen", "保存校验失败: 未选择分类")
                            return@Button
                        }
                        if (isSaving) {
                            AppLogger.i("AddEditTxScreen", "保存拦截: isSaving=$isSaving")
                            return@Button
                        }
                        isSaving = true
                        AppLogger.i("AddEditTxScreen", "保存按钮点击: isEditMode=$isEditMode, transactionId=$transactionId")
                        val transaction = Transaction(
                            id = transactionId ?: 0L,
                            type = selectedType,
                            amount = amountFen,          // 元 → 分，整数解析无精度损失
                            categoryId = selectedCategoryId,
                            paymentMethod = selectedPayment,
                            customPaymentMethod = selectedCustomMethod,
                            note = note.trim(),
                            date = selectedDate,
                            createdAt = if (originalCreatedAt > 0L) originalCreatedAt
                                       else System.currentTimeMillis()
                        )
                        AppLogger.i("AddEditTxScreen", "准备保存: type=$selectedType, amount=$amountFen, categoryId=$selectedCategoryId, date=$selectedDate")
                        if (isEditMode) {
                            viewModel.updateTransaction(transaction) {
                                AppLogger.i("AddEditTxScreen", "update完成，触发onBack")
                                onBack()
                            }
                        } else {
                            viewModel.addTransaction(transaction) {
                                AppLogger.i("AddEditTxScreen", "add完成，触发onBack")
                                onBack()
                            }
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
    }

    // 日期选择器
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            // DatePicker 期望 UTC 零点，需把本地日期转过去，否则打开时会高亮前一天
            initialSelectedDateMillis = DateTimeUtils.localDateToUtcPickerMillis(selectedDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        selectedDate = DateTimeUtils.utcPickerMillisToLocalDate(it)
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

    // 添加自定义方式弹窗
    if (showAddMethodDialog) {
        var methodError by remember { mutableStateOf("") }
        val dialogTitle = if (selectedType == TransactionType.INCOME)
            stringResource(R.string.add_collection_method)
        else
            stringResource(R.string.add_payment_method)
        val errorEmpty = stringResource(R.string.name_cannot_be_empty)
        val errorExists = stringResource(R.string.payment_method_already_exists)
        val labelText = stringResource(R.string.payment_method_name)
        val addBtn = stringResource(R.string.add_button)
        val cancelBtn = stringResource(R.string.cancel)
        val scope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = {
                showAddMethodDialog = false
                newMethodName = ""
                methodError = ""
            },
            title = { Text(dialogTitle) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newMethodName,
                        onValueChange = { newMethodName = it; methodError = "" },
                        label = { Text(labelText) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = methodError.isNotEmpty(),
                        supportingText = if (methodError.isNotEmpty()) {
                            { Text(methodError, color = MaterialTheme.colorScheme.error) }
                        } else null
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newMethodName.trim()
                        if (name.isEmpty()) {
                            methodError = errorEmpty
                            return@TextButton
                        }
                        val exists = customMethods.any { it.name == name }
                        if (exists) {
                            methodError = errorExists
                            return@TextButton
                        }
                        val newMethod = PaymentMethodEntity(name = name, type = selectedType)
                        scope.launch {
                            viewModel.paymentMethodRepository.add(newMethod)
                            selectedPayment = PaymentMethod.OTHER
                            selectedCustomMethod = name
                            showAddMethodDialog = false
                            newMethodName = ""
                            methodError = ""
                        }
                    },
                    enabled = newMethodName.trim().isNotEmpty()
                ) { Text(addBtn) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddMethodDialog = false
                    newMethodName = ""
                    methodError = ""
                }) { Text(cancelBtn) }
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
