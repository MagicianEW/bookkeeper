package com.simplebookkeeper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.model.SavingType
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSavingDialog(
    currentBalance: Long = 0L,
    onConfirm: (type: SavingType, amountYuan: Double, note: String, date: Date) -> Unit,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(SavingType.DEPOSIT) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var year by remember {
        val cal = Calendar.getInstance()
        mutableStateOf(cal.get(Calendar.YEAR))
    }
    var month by remember {
        val cal = Calendar.getInstance()
        mutableStateOf(cal.get(Calendar.MONTH))
    }
    var day by remember {
        val cal = Calendar.getInstance()
        mutableStateOf(cal.get(Calendar.DAY_OF_MONTH))
    }
    var error by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    val invalidAmountError = stringResource(R.string.invalid_amount)
    val insufficientBalanceError = stringResource(R.string.insufficient_balance)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_saving)) },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = type == SavingType.DEPOSIT,
                        onClick = { type = SavingType.DEPOSIT },
                        label = { Text(stringResource(R.string.deposit)) },
                        leadingIcon = if (type == SavingType.DEPOSIT) {
                            { Icon(Icons.Default.Savings, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = type == SavingType.WITHDRAW,
                        onClick = { type = SavingType.WITHDRAW },
                        label = { Text(stringResource(R.string.withdraw)) },
                        leadingIcon = if (type == SavingType.WITHDRAW) {
                            { Icon(Icons.Default.SouthWest, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; error = "" },
                    label = { Text(stringResource(R.string.amount)) },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = error.isNotEmpty()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = "${year}-${(month + 1).toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}",
                    onValueChange = {},
                    label = { Text(stringResource(R.string.date)) },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.saving_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (error.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.toDoubleOrNull()
                when {
                    amount == null || amount <= 0 -> error = invalidAmountError
                    type == SavingType.WITHDRAW && (amount * 100).toLong() > currentBalance -> error = insufficientBalanceError
                    else -> {
                        val cal = Calendar.getInstance().apply {
                            set(year, month, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onConfirm(type, amount, note.trim(), cal.time)
                    }
                }
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Calendar.getInstance().apply {
                set(year, month, day)
            }.timeInMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val cal = Calendar.getInstance().apply { timeInMillis = millis }
                        year = cal.get(Calendar.YEAR)
                        month = cal.get(Calendar.MONTH)
                        day = cal.get(Calendar.DAY_OF_MONTH)
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
}
